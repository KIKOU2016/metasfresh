package de.metas.material.dispo.service.event.handler;

import static de.metas.util.Check.fail;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.TreeSet;

import org.adempiere.exceptions.AdempiereException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableSet;

import de.metas.Profiles;
import de.metas.material.dispo.commons.candidate.Candidate;
import de.metas.material.dispo.commons.candidate.Candidate.CandidateBuilder;
import de.metas.material.dispo.commons.candidate.CandidateType;
import de.metas.material.dispo.commons.candidate.TransactionDetail;
import de.metas.material.dispo.commons.candidate.businesscase.DemandDetail;
import de.metas.material.dispo.commons.candidate.businesscase.DistributionDetail;
import de.metas.material.dispo.commons.candidate.businesscase.Flag;
import de.metas.material.dispo.commons.candidate.businesscase.ProductionDetail;
import de.metas.material.dispo.commons.candidate.businesscase.PurchaseDetail;
import de.metas.material.dispo.commons.repository.CandidateRepositoryRetrieval;
import de.metas.material.dispo.commons.repository.query.CandidatesQuery;
import de.metas.material.dispo.commons.repository.query.DemandDetailsQuery;
import de.metas.material.dispo.commons.repository.query.DistributionDetailsQuery;
import de.metas.material.dispo.commons.repository.query.MaterialDescriptorQuery;
import de.metas.material.dispo.commons.repository.query.ProductionDetailsQuery;
import de.metas.material.dispo.commons.repository.query.PurchaseDetailsQuery;
import de.metas.material.dispo.service.candidatechange.CandidateChangeService;
import de.metas.material.event.MaterialEventHandler;
import de.metas.material.event.PostMaterialEventService;
import de.metas.material.event.commons.HUDescriptor;
import de.metas.material.event.commons.MaterialDescriptor;
import de.metas.material.event.picking.PickingRequestedEvent;
import de.metas.material.event.transactions.AbstractTransactionEvent;
import de.metas.material.event.transactions.TransactionCreatedEvent;
import de.metas.material.event.transactions.TransactionDeletedEvent;
import de.metas.util.Check;
import de.metas.util.Loggables;
import de.metas.util.lang.CoalesceUtil;
import lombok.NonNull;

/*
 * #%L
 * metasfresh-material-dispo
 * %%
 * Copyright (C) 2017 metas GmbH
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
@Service
@Profile(Profiles.PROFILE_MaterialDispo)
public class TransactionEventHandler implements MaterialEventHandler<AbstractTransactionEvent>
{
	private final CandidateChangeService candidateChangeHandler;
	private final CandidateRepositoryRetrieval candidateRepository;
	private final PostMaterialEventService postMaterialEventService;

	public TransactionEventHandler(
			@NonNull final CandidateChangeService candidateChangeHandler,
			@NonNull final CandidateRepositoryRetrieval candidateRepository,
			@NonNull final PostMaterialEventService postMaterialEventService)
	{
		this.candidateChangeHandler = candidateChangeHandler;
		this.candidateRepository = candidateRepository;
		this.postMaterialEventService = postMaterialEventService;
	}

	@Override
	public Collection<Class<? extends AbstractTransactionEvent>> getHandeledEventType()
	{
		return ImmutableList.of(TransactionCreatedEvent.class, TransactionDeletedEvent.class);
	}

	@Override
	public void handleEvent(@NonNull final AbstractTransactionEvent event)
	{
		final List<Candidate> candidates = createCandidatesForTransactionEvent(event);
		for (final Candidate candidate : candidates)
		{
			candidateChangeHandler.onCandidateNewOrChange(candidate);
		}
	}

	@VisibleForTesting
	List<Candidate> createCandidatesForTransactionEvent(@NonNull final AbstractTransactionEvent event)
	{
		final List<Candidate> candidates = new ArrayList<>();

		if (event.getShipmentScheduleIds2Qtys() != null && !event.getShipmentScheduleIds2Qtys().isEmpty())
		{
			candidates.addAll(prepareCandidatesForShipmentScheduleIds(event));
		}
		else if (event.getReceiptScheduleIds2Qtys() != null && !event.getReceiptScheduleIds2Qtys().isEmpty())
		{
			candidates.addAll(prepareCandidateForReceiptScheduleIds(event));
		}
		else if (event.getPpOrderId() > 0)
		{
			final List<Candidate> candidateForPPorder = createCandidateForPPorder(event);
			firePickRequiredEventIfFeasible(candidateForPPorder.get(0), event);

			candidates.addAll(candidateForPPorder);
		}
		else if (event.getDdOrderLineId() > 0)
		{
			final List<Candidate> candidateForDDorder = createCandidateForDDorder(event);
			firePickRequiredEventIfFeasible(candidateForDDorder.get(0), event);

			candidates.addAll(candidateForDDorder);
		}
		else
		{
			candidates.addAll(prepareUnrelatedCandidate(event));
		}
		return candidates;
	}

	private void firePickRequiredEventIfFeasible(
			@NonNull final Candidate candidate,
			@NonNull final AbstractTransactionEvent transactionEvent)
	{
		if (transactionEvent instanceof TransactionDeletedEvent)
		{
			return;
		}

		final Flag pickDirectlyIfFeasible = extractPickDirectlyIfFeasible(candidate);
		if (!pickDirectlyIfFeasible.isTrue())
		{
			Loggables.addLog("Not posting PickingRequestedEvent: this event's candidate has pickDirectlyIfFeasible={}; candidate={}",
					pickDirectlyIfFeasible, candidate);
			return;
		}

		final DemandDetail demandDetail = candidate.getDemandDetail();
		final boolean noShipmentScheduleForPicking = demandDetail == null || demandDetail.getShipmentScheduleId() <= 0;
		if (noShipmentScheduleForPicking)
		{
			Loggables.addLog("Not posting PickingRequestedEvent: this event's candidate has no shipmentScheduleId; candidate={}",
					candidate);
			return;
		}

		final Collection<HUDescriptor> huOnHandQtyChangeDescriptors = transactionEvent.getHuOnHandQtyChangeDescriptors();
		final boolean noHUsToPick = huOnHandQtyChangeDescriptors == null || huOnHandQtyChangeDescriptors.isEmpty();
		if (noHUsToPick)
		{
			Loggables.addLog("Not posting PickingRequestedEvent: this event has no HuOnHandQtyChangeDescriptors");
			return;
		}

		final ImmutableSet<Integer> huIdsToPick = huOnHandQtyChangeDescriptors.stream()
				.filter(huDescriptor -> huDescriptor.getQuantity().signum() > 0)
				.map(HUDescriptor::getHuId)
				.collect(ImmutableSet.toImmutableSet());

		final PickingRequestedEvent pickingRequestedEvent = PickingRequestedEvent.builder()
				.eventDescriptor(transactionEvent.getEventDescriptor())
				.shipmentScheduleId(demandDetail.getShipmentScheduleId())
				.topLevelHuIdsToPick(huIdsToPick)
				.build();

		postMaterialEventService.postEventAfterNextCommit(pickingRequestedEvent);
	}

	private Flag extractPickDirectlyIfFeasible(@NonNull final Candidate candidate)
	{
		final Flag pickDirectlyIfFeasible;
		switch (candidate.getBusinessCase())
		{
			case PRODUCTION:
				pickDirectlyIfFeasible = ProductionDetail
						.cast(candidate.getBusinessCaseDetail())
						.getPickDirectlyIfFeasible();
				break;
			case DISTRIBUTION:
				pickDirectlyIfFeasible = DistributionDetail
						.cast(candidate.getBusinessCaseDetail())
						.getPickDirectlyIfFeasible();
				break;
			default:
				pickDirectlyIfFeasible = null;
				Check.fail("Unsupported business case {}; candidate={}",
						candidate.getBusinessCase(), candidate);
		}
		return pickDirectlyIfFeasible;
	}

	private List<Candidate> prepareCandidatesForShipmentScheduleIds(@NonNull final AbstractTransactionEvent event)
	{
		final Map<Integer, BigDecimal> shipmentScheduleIds2Qtys = event.getShipmentScheduleIds2Qtys();

		final Builder<Candidate> result = ImmutableList.builder();

		for (final Entry<Integer, BigDecimal> shipmentScheduleId2Qty : shipmentScheduleIds2Qtys.entrySet())
		{
			final List<Candidate> candidates = createCandidateForShipmentSchedule(event, shipmentScheduleId2Qty);
			result.addAll(candidates);
		}
		return result.build();
	}

	private List<Candidate> createCandidateForShipmentSchedule(
			@NonNull final AbstractTransactionEvent event,
			@NonNull final Entry<Integer, BigDecimal> shipmentScheduleId2Qty)
	{
		final DemandDetailsQuery demandDetailsQuery = DemandDetailsQuery.ofShipmentScheduleId(shipmentScheduleId2Qty.getKey());

		final CandidatesQuery query = CandidatesQuery.builder()
				.type(CandidateType.DEMAND)
				// only search via demand detail ..it's precise enough; the product and warehouse will also match, but e.g. the date might not!
				.demandDetailsQuery(demandDetailsQuery)
				.build();
		final Candidate existingCandidate = retrieveBestMatchingCandidateOrNull(query, event);

		final List<Candidate> candidates;

		final boolean unrelatedNewTransaction = existingCandidate == null && event instanceof TransactionCreatedEvent;
		if (unrelatedNewTransaction)
		{
			final DemandDetail demandDetail = DemandDetail.forShipmentScheduleIdAndOrderLineId(
					shipmentScheduleId2Qty.getKey(),
					-1,
					-1,
					shipmentScheduleId2Qty.getValue());

			final CandidateBuilder builder = createBuilderForNewUnrelatedCandidate(
					(TransactionCreatedEvent)event,
					shipmentScheduleId2Qty.getValue());

			final Candidate candidate = builder
					.businessCaseDetail(demandDetail)
					.transactionDetail(createTransactionDetail(event))
					.build();
			candidates = ImmutableList.of(candidate);
		}
		else if (existingCandidate != null)
		{
			candidates = createOneOrTwoCandidatesWithChangedTransactionDetailAndQuantity(
					existingCandidate,
					createTransactionDetail(event));
		}
		else
		{
			throw createExceptionForUnexpectedEvent(event);
		}
		return candidates;
	}

	private List<Candidate> prepareCandidateForReceiptScheduleIds(@NonNull final AbstractTransactionEvent event)
	{
		final Map<Integer, BigDecimal> receiptScheduleIds2Qtys = event.getReceiptScheduleIds2Qtys();

		final Builder<Candidate> result = ImmutableList.builder();

		for (final Entry<Integer, BigDecimal> receiptScheduleId2Qty : receiptScheduleIds2Qtys.entrySet())
		{
			final List<Candidate> candidates = createCandidateForReceiptSchedule(event, receiptScheduleId2Qty);
			result.addAll(candidates);
		}
		return result.build();
	}

	/** uses PurchaseDetails.receiptScheduleRepoId to find out if a candidate already exists */
	private List<Candidate> createCandidateForReceiptSchedule(
			@NonNull final AbstractTransactionEvent event,
			@NonNull final Entry<Integer, BigDecimal> receiptScheduleId2Qty)
	{
		final List<Candidate> candidates;
		final TransactionDetail transactionDetailOfEvent = createTransactionDetail(event);

		final PurchaseDetailsQuery purchaseDetailsQuery = PurchaseDetailsQuery.builder()
				.receiptScheduleRepoId(receiptScheduleId2Qty.getKey())
				.build();

		final CandidatesQuery query = CandidatesQuery.builder()
				.type(CandidateType.SUPPLY) // without it we might get stock candidates which we don't want
				.purchaseDetailsQuery(purchaseDetailsQuery)
				.build();

		final Candidate existingCandidate = retrieveBestMatchingCandidateOrNull(query, event);

		final boolean unrelatedNewTransaction = existingCandidate == null && event instanceof TransactionCreatedEvent;
		if (unrelatedNewTransaction)
		{
			// prepare the purchase detail with our inoutLineId
			final PurchaseDetail purchaseDetail = PurchaseDetail.builder()
					.advised(Flag.FALSE_DONT_UPDATE)
					.qty(receiptScheduleId2Qty.getValue())
					.receiptScheduleRepoId(receiptScheduleId2Qty.getKey())
					.build();

			final Candidate candidate = createBuilderForNewUnrelatedCandidate(
					(TransactionCreatedEvent)event,
					event.getQuantity())
							.businessCaseDetail(purchaseDetail)
							.transactionDetail(transactionDetailOfEvent)
							.build();
			candidates = ImmutableList.of(candidate);

		}
		else if (existingCandidate != null)
		{
			candidates = createOneOrTwoCandidatesWithChangedTransactionDetailAndQuantity(
					existingCandidate,
					transactionDetailOfEvent);
		}
		else
		{
			throw createExceptionForUnexpectedEvent(event);
		}
		return candidates;
	}

	private List<Candidate> createCandidateForPPorder(@NonNull final AbstractTransactionEvent event)
	{
		final List<Candidate> candidates;
		final TransactionDetail transactionDetailOfEvent = createTransactionDetail(event);

		final int ppOrderLineIdForQuery = event.getPpOrderLineId() > 0
				? event.getPpOrderLineId()
				: ProductionDetailsQuery.NO_PP_ORDER_LINE_ID;

		final ProductionDetailsQuery productionDetailsQuery = ProductionDetailsQuery.builder()
				.ppOrderId(event.getPpOrderId())
				.ppOrderLineId(ppOrderLineIdForQuery)
				.build();

		final CandidatesQuery query = CandidatesQuery.builder()
				.productionDetailsQuery(productionDetailsQuery)
				.build();

		final Candidate existingCandidate = retrieveBestMatchingCandidateOrNull(query, event);

		final boolean unrelatedNewTransaction = existingCandidate == null && event instanceof TransactionCreatedEvent;
		if (unrelatedNewTransaction)
		{
			final ProductionDetail productionDetail = productionDetailsQuery
					.toProductionDetailBuilder()
					.advised(Flag.FALSE_DONT_UPDATE)
					.pickDirectlyIfFeasible(Flag.FALSE_DONT_UPDATE)
					.qty(event.getQuantity())
					.build();

			final Candidate candidate = createBuilderForNewUnrelatedCandidate(
					(TransactionCreatedEvent)event,
					event.getQuantity())
							.businessCaseDetail(productionDetail)
							.transactionDetail(transactionDetailOfEvent)
							.build();
			candidates = ImmutableList.of(candidate);
		}
		else if (existingCandidate != null)
		{
			candidates = createOneOrTwoCandidatesWithChangedTransactionDetailAndQuantity(
					existingCandidate,
					transactionDetailOfEvent);
		}
		else
		{
			throw createExceptionForUnexpectedEvent(event);
		}
		return candidates;
	}

	private List<Candidate> createCandidateForDDorder(@NonNull final AbstractTransactionEvent event)
	{
		final List<Candidate> candidates;
		final TransactionDetail transactionDetailOfEvent = createTransactionDetail(event);

		final DistributionDetailsQuery distributionDetailsQuery = DistributionDetailsQuery.builder()
				.ddOrderLineId(event.getDdOrderLineId())
				.ddOrderId(event.getDdOrderId())
				.build();

		final CandidatesQuery query = CandidatesQuery.builder()
				.distributionDetailsQuery(distributionDetailsQuery) // only search via distribution detail, ..the product and warehouse will also match, but e.g. the date probably won't!
				.build();

		final Candidate existingCandidate = retrieveBestMatchingCandidateOrNull(query, event);

		final boolean unrelatedNewTransaction = existingCandidate == null && event instanceof TransactionCreatedEvent;
		if (unrelatedNewTransaction)
		{
			final DistributionDetail distributionDetail = distributionDetailsQuery
					.toDistributionDetailBuilder()
					.qty(event.getQuantity())
					.build();

			final Candidate candidate = createBuilderForNewUnrelatedCandidate(
					(TransactionCreatedEvent)event,
					event.getQuantity())
							.businessCaseDetail(distributionDetail)
							.transactionDetail(transactionDetailOfEvent)
							.build();
			candidates = ImmutableList.of(candidate);
		}
		else if (existingCandidate != null)
		{
			candidates = createOneOrTwoCandidatesWithChangedTransactionDetailAndQuantity(
					existingCandidate,
					transactionDetailOfEvent);
		}
		else
		{
			throw createExceptionForUnexpectedEvent(event);
		}
		return candidates;
	}

	private Candidate retrieveBestMatchingCandidateOrNull(
			@NonNull final CandidatesQuery queryWithoutAttributesKey,
			@NonNull final AbstractTransactionEvent transactionEvent)
	{
		final MaterialDescriptorQuery materialDescriptorQuery = MaterialDescriptorQuery
				.builder()
				.storageAttributesKey(transactionEvent.getMaterialDescriptor().getStorageAttributesKey())
				.build();

		final CandidatesQuery queryWithAttributesKey = queryWithoutAttributesKey
				.withMaterialDescriptorQuery(materialDescriptorQuery)
				.withMatchExactStorageAttributesKey(true);

		final Candidate existingCandidate = CoalesceUtil.coalesceSuppliers(
				() -> candidateRepository.retrieveLatestMatchOrNull(queryWithAttributesKey),
				() -> candidateRepository.retrieveLatestMatchOrNull(queryWithoutAttributesKey));
		return existingCandidate;
	}

	private AdempiereException createExceptionForUnexpectedEvent(@NonNull final AbstractTransactionEvent event)
	{
		return new AdempiereException("AbstractTransactionEvent with unexpected type and not-yet-existing candidate")
				.appendParametersToMessage()
				.setParameter("abstractTransactionEvent", event);
	}

	private TransactionDetail createTransactionDetail(@NonNull final AbstractTransactionEvent event)
	{
		final TransactionDetail transactionDetailOfEvent = TransactionDetail.builder()
				.complete(true)
				.quantity(getQuantityDelta(event)) // quantity and storageAttributesKey won't be used in the query, but in the following insert or update
				.storageAttributesKey(event.getMaterialDescriptor().getStorageAttributesKey())
				.attributeSetInstanceId(event.getMaterialDescriptor().getAttributeSetInstanceId())
				.transactionId(event.getTransactionId())
				.transactionDate(event.getMaterialDescriptor().getDate())
				.complete(true)
				.build();
		return transactionDetailOfEvent;
	}

	/**
	 * For {@link TransactionCreatedEvent} we always return a positive quantity; for {@link TransactionDeletedEvent} always a negative one;
	 * That because basically in material dispo, we operate with positive quantities, also if the candidate's type is demand, stock-down etc.
	 */
	private final BigDecimal getQuantityDelta(@NonNull final AbstractTransactionEvent event)
	{
		if (event instanceof TransactionCreatedEvent)
		{
			return event.getQuantityDelta().abs();
		}
		else if (event instanceof TransactionDeletedEvent)
		{
			return event.getQuantityDelta().abs().negate();
		}

		fail("Unexpected subclass of AbstractTransactionEvent; event={}", event);
		return null;
	}

	private List<Candidate> prepareUnrelatedCandidate(@NonNull final AbstractTransactionEvent event)
	{
		final List<Candidate> candidates;
		final TransactionDetail transactionDetailOfEvent = createTransactionDetail(event);

		final CandidatesQuery query = CandidatesQuery.builder()
				.transactionDetail(TransactionDetail.forQuery(event.getTransactionId()))
				.build();
		final Candidate existingCandidate = candidateRepository.retrieveLatestMatchOrNull(query);

		final boolean unrelatedNewTransaction = existingCandidate == null && event instanceof TransactionCreatedEvent;
		if (unrelatedNewTransaction)
		{
			final Candidate candidate = createBuilderForNewUnrelatedCandidate(
					(TransactionCreatedEvent)event,
					event.getQuantity())
							.transactionDetail(transactionDetailOfEvent)
							.build();
			candidates = ImmutableList.of(candidate);
		}
		else if (existingCandidate != null)
		{
			candidates = createOneOrTwoCandidatesWithChangedTransactionDetailAndQuantity(
					existingCandidate,
					transactionDetailOfEvent);
		}
		else
		{
			throw createExceptionForUnexpectedEvent(event);
		}
		return candidates;
	}

	/**
	 *
	 * Returns a list with one or two candidates.
	 * The list's first item always contains the given {@code changedTransactionDetail}.
	 * <p>
	 * If the given {@code changedTransactionDetail}'s attributes match the given candidate's attributes, then the returned list has one item.
	 * Otherwise it has two items with the first item containing *only* the changedTransactionDetail and the second item being the given {@code candidate}, but without the given {@code changedTransactionDetail}.
	 */
	@VisibleForTesting
	List<Candidate> createOneOrTwoCandidatesWithChangedTransactionDetailAndQuantity(
			@NonNull final Candidate candidate,
			@NonNull final TransactionDetail changedTransactionDetail)
	{
		final boolean transactionMatchesCandidate = Objects
				.equals(
						candidate.getMaterialDescriptor().getStorageAttributesKey(),
						changedTransactionDetail.getStorageAttributesKey());

		if (transactionMatchesCandidate)
		{
			final TreeSet<TransactionDetail> newTransactionDetailsSet = extractAllTransactionDetails(candidate, changedTransactionDetail);

			final Instant firstTransactionDate = extractMinTransactionDate(newTransactionDetailsSet);

			final Candidate withTransactionDetails = candidate
					.withTransactionDetails(ImmutableList.copyOf(newTransactionDetailsSet))
					.withDate(firstTransactionDate);
			final BigDecimal actualQty = withTransactionDetails.computeActualQty();
			final BigDecimal detailQty = candidate.getDetailQty();

			return ImmutableList.of(withTransactionDetails.withQuantity(actualQty.max(detailQty)));
		}
		else
		{
			// create a copy of candidate, just with
			// * the transaction's ASI/storage-key
			// * plannedQty == actualQty == transactionQty
			// * the transactionDetail added to it
			final MaterialDescriptor newMaterialDescriptor = candidate
					.getMaterialDescriptor()
					.withStorageAttributes(
							changedTransactionDetail.getStorageAttributesKey(),
							changedTransactionDetail.getAttributeSetInstanceId())
					.withQuantity(changedTransactionDetail.getQuantity())
					.withDate(changedTransactionDetail.getTransactionDate());

			final Candidate newCandidate = candidate
					.toBuilder()
					.id(null)
					.parentId(null) // important to make sure a supply new candidate gets a stock record
					.materialDescriptor(newMaterialDescriptor)
					.clearTransactionDetails()
					.transactionDetail(changedTransactionDetail)
					.build();

			// subtract the transaction's Qty from the candidate;
			// because we don't expect that quantity anymore. It just came, but with different attributes.
			final BigDecimal actualQty = candidate.computeActualQty();
			final BigDecimal plannedQty = candidate.getDetailQty().subtract(changedTransactionDetail.getQuantity());
			final BigDecimal updatedQty = actualQty.max(plannedQty);
			final Candidate updatedCandidate = candidate.withQuantity(updatedQty);

			// return the subtracted-qty-candidate and the copy
			return ImmutableList.of(newCandidate, updatedCandidate);
		}
	}

	private Instant extractMinTransactionDate(@NonNull final TreeSet<TransactionDetail> transactionDetailsSet)
	{
		final Instant firstTransactionDate = transactionDetailsSet
				.stream()
				.min(Comparator.comparing(TransactionDetail::getTransactionDate))
				.get() // we know there is at least changedTransactionDetail, so we can call get() witch confidence
				.getTransactionDate();
		return firstTransactionDate;
	}

	private TreeSet<TransactionDetail> extractAllTransactionDetails(
			@NonNull final Candidate candidate,
			@NonNull final TransactionDetail changedTransactionDetail)
	{
		final ImmutableList<TransactionDetail> otherTransactionDetails = candidate.getTransactionDetails()
				.stream()
				.filter(transactionDetail -> transactionDetail.getTransactionId() != changedTransactionDetail.getTransactionId())
				.collect(ImmutableList.toImmutableList());

		// note: using TreeSet to make sure we don't end up with duplicated transactionDetails
		final TreeSet<TransactionDetail> newTransactionDetailsSet = new TreeSet<>(Comparator.comparing(TransactionDetail::getTransactionId));
		newTransactionDetailsSet.addAll(otherTransactionDetails);
		newTransactionDetailsSet.add(changedTransactionDetail);
		return newTransactionDetailsSet;
	}

	/**
	 * @param transactionCreatedEvent note that creating a new candidate doesn't make sense for a {@link TransactionDeletedEvent}
	 */
	@VisibleForTesting
	static CandidateBuilder createBuilderForNewUnrelatedCandidate(
			@NonNull final TransactionCreatedEvent transactionCreatedEvent,
			@NonNull final BigDecimal quantity)
	{
		final CandidateBuilder builder = Candidate
				.builderForEventDescr(transactionCreatedEvent.getEventDescriptor());
		if (quantity.signum() <= 0)
		{
			return builder.type(CandidateType.UNRELATED_DECREASE)
					.materialDescriptor(transactionCreatedEvent.getMaterialDescriptor().withQuantity(quantity.negate()));
		}
		else
		{
			return builder.type(CandidateType.UNRELATED_INCREASE)
					.materialDescriptor(transactionCreatedEvent.getMaterialDescriptor());
		}
	}
}
