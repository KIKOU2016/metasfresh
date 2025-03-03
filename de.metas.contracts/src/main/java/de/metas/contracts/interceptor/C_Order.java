package de.metas.contracts.interceptor;

import java.util.List;

import org.adempiere.ad.modelvalidator.annotations.DocValidate;
import org.adempiere.ad.modelvalidator.annotations.Interceptor;
import org.adempiere.ad.modelvalidator.annotations.ModelChange;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.compiere.Adempiere;
import org.compiere.model.I_C_Customer_Retention;
import org.compiere.model.ModelValidator;

/*
 * #%L
 * de.metas.contracts
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

import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import de.metas.adempiere.model.I_C_Order;
import de.metas.bpartner.BPartnerId;
import de.metas.contracts.IFlatrateBL;
import de.metas.contracts.IFlatrateBL.ContractExtendingRequest;
import de.metas.contracts.impl.CustomerRetentionRepository;
import de.metas.contracts.model.I_C_Flatrate_Conditions;
import de.metas.contracts.model.I_C_Flatrate_Term;
import de.metas.contracts.model.I_C_Flatrate_Transition;
import de.metas.contracts.model.X_C_Flatrate_Term;
import de.metas.contracts.model.X_C_Flatrate_Transition;
import de.metas.contracts.order.ContractOrderService;
import de.metas.contracts.order.model.I_C_OrderLine;
import de.metas.contracts.subscription.ISubscriptionBL;
import de.metas.contracts.subscription.ISubscriptionDAO;
import de.metas.document.DocTypeId;
import de.metas.document.IDocTypeBL;
import de.metas.invoicecandidate.api.IInvoiceCandDAO;
import de.metas.invoicecandidate.model.I_C_Invoice_Candidate;
import de.metas.logging.LogManager;
import de.metas.order.IOrderDAO;
import de.metas.order.OrderId;
import de.metas.util.Check;
import de.metas.util.Services;

@Interceptor(I_C_Order.class)
@Component
public class C_Order
{
	private static final Logger logger = LogManager.getLogger(C_Order.class);

	private static final String MSG_ORDER_DATE_ORDERED_CHANGE_FORBIDDEN_1P = "Order_DateOrdered_Change_Forbidden";

	@ModelChange( //
			timings = ModelValidator.TYPE_BEFORE_CHANGE, //
			ifColumnsChanged = I_C_Order.COLUMNNAME_DateOrdered)
	public void updateDataEntry(final I_C_Order order)
	{
		final IOrderDAO orderDAO = Services.get(IOrderDAO.class);
		final IInvoiceCandDAO invoiceCandDB = Services.get(IInvoiceCandDAO.class);

		for (final I_C_OrderLine ol : orderDAO.retrieveOrderLines(order, I_C_OrderLine.class))
		{
			for (final I_C_Invoice_Candidate icOfOl : invoiceCandDB.retrieveReferencing(ol))
			{
				if (icOfOl.isToClear())
				{
					throw new AdempiereException(MSG_ORDER_DATE_ORDERED_CHANGE_FORBIDDEN_1P, new Object[] { ol.getLine() });
				}
			}
		}
	}

	@DocValidate(timings = { ModelValidator.TIMING_AFTER_COMPLETE })
	public void handleComplete(final I_C_Order order)
	{
		final DocTypeId docTypeId = DocTypeId.ofRepoId(order.getC_DocType_ID());
		if (Services.get(IDocTypeBL.class).isSalesProposalOrQuotation(docTypeId))
		{
			return;
		}

		final List<I_C_OrderLine> orderLines = Services.get(IOrderDAO.class).retrieveOrderLines(order, I_C_OrderLine.class);
		for (final I_C_OrderLine ol : orderLines)
		{
			if (ol.getC_Flatrate_Conditions_ID() <= 0)
			{
				logger.debug("Order line " + ol + " has no subscription");
				continue;
			}
			handleOrderLineComplete(ol);
		}
	}

	private void handleOrderLineComplete(final I_C_OrderLine ol)
	{
		final ISubscriptionDAO subscriptionDAO = Services.get(ISubscriptionDAO.class);
		if (subscriptionDAO.existsTermForOl(ol))
		{
			logger.debug("{} is already already referenced by a C_Flatrate_Term record ", ol);
			return;
		}

		logger.info("Creating new {} entry", I_C_Flatrate_Term.Table_Name);

		final boolean completeIt = true;
		final I_C_Flatrate_Term newSc = Services.get(ISubscriptionBL.class).createSubscriptionTerm(ol, completeIt);

		Check.assume(
				X_C_Flatrate_Term.DOCSTATUS_Completed.equals(newSc.getDocStatus()),
				"{} has DocStatus={}", newSc, newSc.getDocStatus());
		logger.info("Created and completed {}", newSc);

		extendFlatratetermIfAutoExtension(newSc);
	}

	private void extendFlatratetermIfAutoExtension(final I_C_Flatrate_Term term)
	{
		final I_C_Flatrate_Conditions conditions = term.getC_Flatrate_Conditions();
		final I_C_Flatrate_Transition transition = conditions.getC_Flatrate_Transition();

		if (X_C_Flatrate_Transition.EXTENSIONTYPE_ExtendAll.equals(transition.getExtensionType())
				&& transition.getC_Flatrate_Conditions_Next_ID() > 0)
		{
			final ContractExtendingRequest request = ContractExtendingRequest.builder()
					.contract(term)
					.forceExtend(true)
					.forceComplete(true)
					.nextTermStartDate(null)
					.build();

			Services.get(IFlatrateBL.class).extendContractAndNotifyUser(request);
		}
	}

	@DocValidate(timings = { ModelValidator.TIMING_AFTER_COMPLETE })
	public void handleReactivate(final I_C_Order order)
	{
		final List<I_C_OrderLine> orderLines = Services.get(IOrderDAO.class).retrieveOrderLines(order, I_C_OrderLine.class);
		for (final I_C_OrderLine ol : orderLines)
		{
			if (ol.getC_Flatrate_Conditions_ID() <= 0)
			{
				logger.debug("Order line " + ol + " has no subscription");
				continue;
			}
			handleOrderLineReactivate(ol);
		}
	}

	/**
	 * Make sure the orderLine still has processed='Y', even if the order is reactivated. <br>
	 * This was apparently added in task 03152.<br>
	 * I can guess that if an order line already has a C_Flatrate_Term, then we don't want that order line to be editable, because it could create inconsistencies with the term.
	 *
	 * @param ol
	 * @param trxName
	 */
	private void handleOrderLineReactivate(final I_C_OrderLine ol)
	{
		logger.info("Setting processed status of subscription order line " + ol + " back to Processed='Y'");

		ol.setProcessed(true);
		InterfaceWrapperHelper.save(ol);
	}

	@DocValidate(timings = { ModelValidator.TIMING_AFTER_COMPLETE })
	public void updateCustomerRetention(final I_C_Order order)
	{

		final ContractOrderService contractOrderService = Adempiere.getBean(ContractOrderService.class);

		final CustomerRetentionRepository customerRetentionRepo = Adempiere.getBean(CustomerRetentionRepository.class);

		if (!order.isSOTrx())
		{
			// nothing to do
			return;
		}

		final OrderId orderId = OrderId.ofRepoId(order.getC_Order_ID());

		if (!contractOrderService.isContractSalesOrder(orderId))
		{
			// nothing to do
			return;
		}

		final BPartnerId bpartnerId = BPartnerId.ofRepoId(order.getC_BPartner_ID());

		I_C_Customer_Retention customerRetention = customerRetentionRepo.retrieveCustomerRetention(bpartnerId);

		if (Check.isEmpty(customerRetention))
		{
			customerRetention = customerRetentionRepo.createNewCustomerRetention(bpartnerId);
		}

		if (Check.isEmpty(customerRetention.getCustomerRetention()))
		{
			customerRetentionRepo.setNewCustomer(bpartnerId);
		}
	}
}
