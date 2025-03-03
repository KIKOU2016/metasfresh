package de.metas.invoicecandidate.api.impl;

import static java.math.BigDecimal.ONE;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;

/*
 * #%L
 * de.metas.swat.base
 * %%
 * Copyright (C) 2015 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import javax.annotation.Nullable;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.ad.trx.processor.api.FailTrxItemExceptionHandler;
import org.adempiere.ad.trx.processor.api.ITrxItemExecutorBuilder.OnItemErrorPolicy;
import org.adempiere.ad.trx.processor.api.ITrxItemProcessorExecutorService;
import org.adempiere.ad.trx.processor.spi.TrxItemChunkProcessorAdapter;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ISysConfigBL;
import org.adempiere.util.lang.IAutoCloseable;
import org.adempiere.util.lang.IContextAware;
import org.compiere.SpringContextHolder;
import org.slf4j.Logger;

import ch.qos.logback.classic.Level;
import de.metas.inout.IInOutDAO;
import de.metas.invoicecandidate.api.IInvoiceCandBL;
import de.metas.invoicecandidate.api.IInvoiceCandDAO;
import de.metas.invoicecandidate.api.IInvoiceCandInvalidUpdater;
import de.metas.invoicecandidate.api.IInvoiceCandRecomputeTagger;
import de.metas.invoicecandidate.api.IInvoiceCandidateHandlerBL;
import de.metas.invoicecandidate.api.InvoiceCandRecomputeTag;
import de.metas.invoicecandidate.internalbusinesslogic.InvoiceCandidate;
import de.metas.invoicecandidate.internalbusinesslogic.InvoiceCandidateRecordService;
import de.metas.invoicecandidate.model.I_C_InvoiceCandidate_InOutLine;
import de.metas.invoicecandidate.model.I_C_Invoice_Candidate;
import de.metas.invoicecandidate.model.I_M_InOutLine;
import de.metas.invoicecandidate.spi.IInvoiceCandidateHandler.PriceAndTax;
import de.metas.lock.api.ILock;
import de.metas.logging.LogManager;
import de.metas.util.Check;
import de.metas.util.Loggables;
import de.metas.util.Services;
import lombok.NonNull;

/* package */class InvoiceCandInvalidUpdater implements IInvoiceCandInvalidUpdater
{
	private static final Logger logger = LogManager.getLogger(InvoiceCandInvalidUpdater.class);

	// services
	private final transient InvoiceCandBL invoiceCandBL;
	private final transient IInvoiceCandDAO invoiceCandDAO = Services.get(IInvoiceCandDAO.class);
	private final transient IInvoiceCandidateHandlerBL invoiceCandidateHandlerBL = Services.get(IInvoiceCandidateHandlerBL.class);
	private final transient IInOutDAO inOutDAO = Services.get(IInOutDAO.class);
	private final transient ISysConfigBL sysConfigBL = Services.get(ISysConfigBL.class);
	private final transient ITrxManager trxManager = Services.get(ITrxManager.class);
	private final transient ITrxItemProcessorExecutorService trxItemProcessorExecutorService = Services.get(ITrxItemProcessorExecutorService.class);

	private static final String SYSCONFIG_ItemsPerBatch = "de.metas.invoicecandidate.api.impl.InvoiceCandInvalidUpdater.ItemsPerBatch";
	private static final int DEFAULT_ItemsPerBatch = 100;

	//
	// Parameters
	private Properties _ctx;
	private String _trxName;
	private final IInvoiceCandRecomputeTagger icTagger;

	//
	// State
	private boolean executed = false;

	InvoiceCandInvalidUpdater(@NonNull final InvoiceCandBL invoiceCandBL)
	{
		this.invoiceCandBL = invoiceCandBL;
		this.icTagger = invoiceCandDAO.tagToRecompute();
	}

	@Override
	public void update()
	{
		markAsExecuted();

		//
		// Tag invoice candidates scheduled to recompute using given "recomputeTag" as the recompute tag marker
		icTagger.tag();

		//
		// Update the tagged invoice candidates
		try
		{
			updateTagged();

			//
			// Remove from "invoice candidates to recompute" all those which were tagged with our tag
			// because now we consider them valid
			// NOTE: usually, this method shall delete 0 records because the recompute records are deleted after each chunk is processed.
			icTagger.deleteAllTaggedAndInvalidateCache();
		}
		catch (final Exception updateException)
		{
			//
			// Release those which were tagged
			try
			{
				icTagger.untag();
			}
			catch (final Exception untagException)
			{
				updateException.addSuppressed(untagException);
			}

			// Propagate the exception
			throw AdempiereException.wrapIfNeeded(updateException);
		}
	}

	/**
	 * Update all invoice candidates which were tagged
	 */
	private final void updateTagged()
	{
		//
		// Determine if we shall process our invoice candidates in batches and commit after each batch.
		// i.e. we shall do this only if we were not asked to update a particular set of invoice candidates.
		final boolean processInBatches = !icTagger.isOnlyC_Invoice_Candidate_IDs();
		final int itemsPerBatch = processInBatches ? getItemsPerBatch() : Integer.MAX_VALUE;

		//
		// Fetch the invoice candidates to update
		final Iterator<I_C_Invoice_Candidate> candidatesToUpdate = icTagger.retrieveInvoiceCandidates();
		if (!candidatesToUpdate.hasNext())
		{
			// no candidates found => nothing to do
			Loggables.withLogger(logger, Level.DEBUG).addLog("icTagger has no invoice candidates to update; nothing to do; icTagger={}", icTagger);
			return;
		}

		//
		// Update invoice candidates in chunks
		final ICUpdateResult result = new ICUpdateResult();
		try (final IAutoCloseable updateInProgressCloseable = invoiceCandBL.setUpdateProcessInProgress())
		{
			trxItemProcessorExecutorService.<I_C_Invoice_Candidate, ICUpdateResult> createExecutor()
					.setContext(getCtx(), getTrxName()) // if called from process or wp-processor then getTrxName() is null because *we* want to manage the trx => commit after each chunk
					.setItemsPerBatch(itemsPerBatch)

					// Don't use trx savepoints because they are expensive and we are not going to rollback anyways (OnItemErrorPolicy.ContinueChunkAndCommit)
					// Note that if our trx is null, then this doesn't matter anyways.
					.setUseTrxSavepoints(false)

					.setExceptionHandler(new ICTrxItemExceptionHandler(result))

					// issue #302: ICTrxItemExceptionHandler will deal with problems, so we just continue if they happen.
					.setOnItemErrorPolicy(OnItemErrorPolicy.ContinueChunkAndCommit)

					.setProcessor(new TrxItemChunkProcessorAdapter<I_C_Invoice_Candidate, ICUpdateResult>()
					{
						/** the invoice candidates which were updated in current batch/chunk */
						final List<Integer> chunkInvoiceCandidateIds = new ArrayList<>();

						@Override
						public void process(final I_C_Invoice_Candidate ic) throws Exception
						{
							chunkInvoiceCandidateIds.add(ic.getC_Invoice_Candidate_ID());

							updateInvalid(ic);
							if (!ic.isError())
							{
								result.addInvoiceCandidate(ic);
							}
							else
							{
								Loggables.withLogger(logger, Level.DEBUG)
										.addLog("Error processing invoice; ic.errorMessage={}; ic={}", ic.getErrorMsg(), ic);
								result.incrementErrorsCount();
							}
						}

						@Override
						public ICUpdateResult getResult()
						{
							return result;
						}

						/**
						 * Always return <code>true</code> and let the caller decide when to close the chunk (based on ItemsPerBatch setting).
						 * We do this because in fact, each IC is independent from each other.
						 * On the other hand, we don't want the overhead of dealing with each IC independently (trx-commit etc).
						 */
						@Override
						public boolean isSameChunk(final I_C_Invoice_Candidate item)
						{
							return true;
						}

						@Override
						public void newChunk(final I_C_Invoice_Candidate item)
						{
							chunkInvoiceCandidateIds.clear(); // better safe than sorry
						}

						/** Delete the items which we just updated from <code>C_Invoice_Candidate_Recompute</code>. */
						@Override
						public void completeChunk()
						{
							icTagger.deleteTaggedAndInvalidateCache(chunkInvoiceCandidateIds);
							chunkInvoiceCandidateIds.clear();
						}
					})
					//
					.process(candidatesToUpdate);
		}

		//
		// Log the result
		Loggables.addLog("Update invalid result: {}", result.getSummary());
	}

	private final void updateInvalid(final I_C_Invoice_Candidate ic)
	{
		final Properties ctx = InterfaceWrapperHelper.getCtx(ic);

		// reset scheduler result
		ic.setSchedulerResult(null);
		invoiceCandBL.resetError(ic);

		// update qtyOrdered. we need to be up to date for that factor
		invoiceCandidateHandlerBL.setOrderedData(ic);

		// i.e. QtyOrdered's signum. Used at different places throughout this method
		final BigDecimal factor;
		if (ic.getQtyOrdered().signum() < 0)
		{
			factor = ONE.negate();
		}
		else
		{
			factor = ONE;
		}

		final BigDecimal oldQtyInvoiced = ic.getQtyInvoiced().multiply(factor);

		// update the Discount value of 'ic'. We will need it for the priceActual further down
		invoiceCandBL.set_Discount(ctx, ic);

		// 06502: add entry in C_InvoiceCandidate_InOutLine to link InvoiceCandidate with inoutLines
		// Note: the code originally related to task 06502 has partially been moved to de.metas.invoicecandidate.modelvalidator.M_InoutLine
		// we'll need those icIols to be up to date to date in order to have QtyWithIssues (updateQtyWithIssues() et al. further down),
		// and we need them (depending on which handler) for setDeliveredData()
		populateC_InvoiceCandidate_InOutLine(ic, ic.getC_OrderLine());

		// Update 'QtyToInvoice_OverrideFulfilled'
		// If is turns out that the fulfillment is now sufficient,
		// reset both 'QtyToInvoice_Override' and 'QtyToInvoice_OverrideFulfilled'
		invoiceCandBL.set_QtyToInvoiceOverrideFulfilled(ic, oldQtyInvoiced, factor);

		final InvoiceCandidateRecordService invoiceCandidateRecordService = SpringContextHolder.instance.getBean(InvoiceCandidateRecordService.class);
		final InvoiceCandidate invoiceCandidate = invoiceCandidateRecordService.ofRecord(ic);
		invoiceCandidateRecordService.updateRecord(invoiceCandidate, ic);

		//
		// Update Price and Quantity only if this invoice candidate was NOT approved for invoicing (08610)
		if (!ic.isApprovalForInvoicing())
		{
			try
			{
				final PriceAndTax priceAndTax = invoiceCandidateHandlerBL.calculatePriceAndTax(ic);
				IInvoiceCandInvalidUpdater.updatePriceAndTax(ic, priceAndTax);
			}
			catch (final Exception ex)
			{
				invoiceCandBL.setError(ic, ex);
			}
		}

		// update BPartner data from 'ic'
		invoiceCandidateHandlerBL.setBPartnerData(ic);

		invoiceCandidateHandlerBL.setInvoiceScheduleAndDateToInvoice(ic);

		invoiceCandBL.set_QtyInvoiced_NetAmtInvoiced_Aggregation0(ctx, ic);

		// 06539 add qty overdelivery to qty delivered
		final org.compiere.model.I_C_OrderLine ol = ic.getC_OrderLine();
		if (ol != null)
		{
			ic.setQtyOrderedOverUnder(ol.getQtyOrderedOverUnder());
		}

		// we'll need both qtyToInvoice/qtyToInvoiceInPriceUOM and priceActual to compute the netAmtToInvoice further down
		invoiceCandBL.setPriceActual_Override(ic);

		invoiceCandBL.setAmountAndDateForFreightCost(ic);

		// Note: ic.setProcessed is not invoked here, but in a model validator
		// That's because QtyToOrder and QtyInvoiced could also be set somewhere else

		// We need to update the NetAmtToInvoice again because in some cases this value depends on overall in invoiceable amount
		// e.g. see ManualCandidateHandler which is calculated how much we can invoice of a credit memo amount
		invoiceCandBL.setNetAmtToInvoice(ic);

		invoiceCandBL.setInvoiceScheduleAmtStatus(ctx, ic);

		//
		// Save it
		invoiceCandDAO.save(ic);
	}

	/**
	 * Link all orderLine's inoutLine to our invoice candidate.
	 */
	private void populateC_InvoiceCandidate_InOutLine(
			@NonNull final I_C_Invoice_Candidate ic,
			@Nullable final org.compiere.model.I_C_OrderLine orderLine)
	{
		if (orderLine == null)
		{
			return; // nothing to do
		}

		final IContextAware context = InterfaceWrapperHelper.getContextAware(ic);

		final List<I_M_InOutLine> inoutLines = inOutDAO.retrieveLinesForOrderLine(orderLine, I_M_InOutLine.class);
		for (final I_M_InOutLine inOutLine : inoutLines)
		{
			if (invoiceCandDAO.existsInvoiceCandidateInOutLinesForInvoiceCandidate(ic, inOutLine))
			{
				continue; // nothing to to, record already exists
			}

			final I_C_InvoiceCandidate_InOutLine iciol = newInstance(I_C_InvoiceCandidate_InOutLine.class, context);
			iciol.setC_Invoice_Candidate(ic);
			Services.get(IInvoiceCandBL.class).updateICIOLAssociationFromIOL(iciol, inOutLine);
		}
	}

	private final void assertNotExecuted()
	{
		Check.assume(!executed, "Updater not executed: {}", this);
	}

	private final void markAsExecuted()
	{
		assertNotExecuted();
		executed = true;
	}

	@Override
	public IInvoiceCandInvalidUpdater setContext(final Properties ctx, final String trxName)
	{
		assertNotExecuted();
		_ctx = ctx;
		_trxName = trxName;
		icTagger.setContext(ctx, trxName);
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setContext(final IContextAware context)
	{
		Check.assumeNotNull(context, "context not null");
		return setContext(context.getCtx(), context.getTrxName());
	}

	private final Properties getCtx()
	{
		Check.assumeNotNull(_ctx, "_ctx not null");
		return _ctx;
	}

	/**
	 * @return actual trxName or {@link ITrx#TRXNAME_ThreadInherited}
	 */
	private final String getTrxName()
	{
		if (trxManager.isNull(_trxName))
		{
			return ITrx.TRXNAME_ThreadInherited;
		}
		return _trxName;
	}

	@Override
	public IInvoiceCandInvalidUpdater setLockedBy(final ILock lockedBy)
	{
		assertNotExecuted();
		icTagger.setLockedBy(lockedBy);
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setTaggedWithNoTag()
	{
		assertNotExecuted();
		icTagger.setTaggedWithNoTag();
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setTaggedWithAnyTag()
	{
		assertNotExecuted();
		icTagger.setTaggedWithAnyTag();
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setLimit(final int limit)
	{
		assertNotExecuted();
		icTagger.setLimit(limit);
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setRecomputeTagToUse(final InvoiceCandRecomputeTag tag)
	{
		assertNotExecuted();
		icTagger.setRecomputeTag(tag);
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setOnlyC_Invoice_Candidates(final Iterator<? extends I_C_Invoice_Candidate> invoiceCandidates)
	{
		assertNotExecuted();
		icTagger.setOnlyC_Invoice_Candidates(invoiceCandidates);
		return this;
	}

	@Override
	public IInvoiceCandInvalidUpdater setOnlyC_Invoice_Candidates(final Iterable<? extends I_C_Invoice_Candidate> invoiceCandidates)
	{
		assertNotExecuted();
		icTagger.setOnlyC_Invoice_Candidates(invoiceCandidates);
		return this;
	}

	private final int getItemsPerBatch()
	{
		return sysConfigBL.getIntValue(SYSCONFIG_ItemsPerBatch, DEFAULT_ItemsPerBatch);
	}

	/**
	 * IC update result.
	 *
	 * @author metas-dev <dev@metasfresh.com>
	 *
	 */
	private static final class ICUpdateResult
	{
		private int countOk = 0;
		private int countErrors = 0;

		public void addInvoiceCandidate(final I_C_Invoice_Candidate ic)
		{
			countOk++;
		}

		public void incrementErrorsCount()
		{
			countErrors++;
		}

		@Override
		public String toString()
		{
			return getSummary();
		}

		public String getSummary()
		{
			return "Updated " + countOk + " invoice candidates, " + countErrors + " errors";
		}
	}

	/**
	 * IC update exception handler
	 */
	private final class ICTrxItemExceptionHandler extends FailTrxItemExceptionHandler
	{
		private final ICUpdateResult result;

		public ICTrxItemExceptionHandler(@NonNull final ICUpdateResult result)
		{
			this.result = result;
		}

		/**
		 * Resets the given IC to its old values, and sets an error flag in it.
		 */
		@Override
		public void onItemError(final Throwable e, final Object item)
		{
			result.incrementErrorsCount();

			final I_C_Invoice_Candidate ic = InterfaceWrapperHelper.create(item, I_C_Invoice_Candidate.class);

			// gh #428: don't discard changes that were already made, because they might include a change of QtyInvoice.
			// in that case, a formerly Processed IC might need to be flagged as unprocessed.
			// if we discard all changes in this case, then we will have IsError='Y' and also an error message in the IC,
			// but the user will probably ignore it, because the IC is still flagged as processed.
			invoiceCandBL.setError(ic, e);
			// invoiceCandBL.discardChangesAndSetError(ic, e);

			invoiceCandDAO.save(ic);
		}
	}
}
