package de.metas.handlingunits.shipmentschedule.api;

import static org.adempiere.model.InterfaceWrapperHelper.load;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.ad.trx.processor.api.FailTrxItemExceptionHandler;
import org.adempiere.exceptions.AdempiereException;
import org.compiere.Adempiere;
import org.compiere.model.I_M_Package;
import org.compiere.model.I_M_Shipper;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import de.metas.handlingunits.IHUShipperTransportationBL;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.shipmentschedule.async.GenerateInOutFromHU.BillAssociatedInvoiceCandidates;
import de.metas.inout.model.I_M_InOut;
import de.metas.inoutcandidate.api.InOutGenerateResult;
import de.metas.invoicecandidate.InvoiceCandidateId;
import de.metas.invoicecandidate.api.IInvoiceCandBL;
import de.metas.invoicecandidate.api.IInvoiceCandDAO;
import de.metas.invoicecandidate.api.IInvoiceCandidateEnqueueResult;
import de.metas.invoicecandidate.api.impl.PlainInvoicingParams;
import de.metas.logging.LogManager;
import de.metas.shipper.gateway.commons.ShipperGatewayFacade;
import de.metas.shipper.gateway.spi.model.DeliveryOrderCreateRequest;
import de.metas.shipping.IShipperDAO;
import de.metas.shipping.ShipperId;
import de.metas.shipping.model.I_M_ShipperTransportation;
import de.metas.util.Check;
import de.metas.util.GuavaCollectors;
import de.metas.util.Loggables;
import de.metas.util.Services;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.ToString;

/*
 * #%L
 * de.metas.handlingunits.base
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

/**
 * Facade which is able to generate shipping documents (generate shipment, add to shipper transportation, generate invoice).
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@ToString(exclude = { "huShipperTransportationBL", "huShipmentScheduleDAO", "huShipmentScheduleBL", "invoiceCandDAO", "invoiceCandBL", "trxManager" })
public class HUShippingFacade
{
	private static final Logger logger = LogManager.getLogger(HUShippingFacade.class);
	private final IHUShipperTransportationBL huShipperTransportationBL = Services.get(IHUShipperTransportationBL.class);
	private final IHUShipmentScheduleDAO huShipmentScheduleDAO = Services.get(IHUShipmentScheduleDAO.class);
	private final IHUShipmentScheduleBL huShipmentScheduleBL = Services.get(IHUShipmentScheduleBL.class);
	private final IInvoiceCandDAO invoiceCandDAO = Services.get(IInvoiceCandDAO.class);
	private final IInvoiceCandBL invoiceCandBL = Services.get(IInvoiceCandBL.class);
	private final ITrxManager trxManager = Services.get(ITrxManager.class);

	private final Supplier<ShipperGatewayFacade> shipperGatewayFacadeSupplier = //
			() -> Adempiere.getBean(ShipperGatewayFacade.class);

	//
	// Parameters
	private final int addToShipperTransportationId;
	private final boolean completeShipments;
	private final BillAssociatedInvoiceCandidates invoiceMode;
	private final boolean createShipperDeliveryOrders;
	private LocalDate _shipperDeliveryOrderPickupDate = null; // lazy, will be fetched from Shipper Transportation
	private final ImmutableList<I_M_HU> hus;
	private final boolean failIfNoShipmentCandidatesFound;

	//
	// State
	private List<ShipmentScheduleWithHU> _candidates; // lazy
	private InOutGenerateResult shipmentsGenerateResult;
	private final ArrayList<I_M_Package> mpackagesCreated = new ArrayList<>();

	@Builder
	private HUShippingFacade(
			@NonNull @Singular("hu") final List<I_M_HU> hus,
			final int addToShipperTransportationId,
			final boolean completeShipments,
			@Nullable final BillAssociatedInvoiceCandidates invoiceMode,
			final boolean createShipperDeliveryOrders,
			final boolean failIfNoShipmentCandidatesFound)
	{
		Check.assumeNotEmpty(hus, "hus is not empty");

		this.hus = ImmutableList.copyOf(hus);
		this.addToShipperTransportationId = addToShipperTransportationId;
		this.completeShipments = completeShipments;
		this.invoiceMode = invoiceMode;
		this.createShipperDeliveryOrders = createShipperDeliveryOrders;
		this.failIfNoShipmentCandidatesFound = failIfNoShipmentCandidatesFound;
	}

	@VisibleForTesting
	public List<ShipmentScheduleWithHU> getCandidates()
	{
		if (_candidates == null)
		{
			_candidates = huShipmentScheduleDAO.retrieveShipmentSchedulesWithHUsFromHUs(hus);
		}
		return _candidates;
	}

	@VisibleForTesting
	public List<I_M_InOut> getGeneratedShipments()
	{
		Check.assumeNotNull(shipmentsGenerateResult, "shipments were generated");
		return shipmentsGenerateResult.getInOuts();
	}

	public InOutGenerateResult generateShippingDocuments()
	{
		trxManager.runInThreadInheritedTrx(this::generateShippingDocuments0);
		return shipmentsGenerateResult;
	}

	private void generateShippingDocuments0()
	{
		addHUsToShipperTransportationIfNeeded();
		generateShipments();
		generateInvoicesIfNeeded();
		generateShipperDeliveryOrdersIfNeeded();
	}

	private void addHUsToShipperTransportationIfNeeded()
	{
		if (addToShipperTransportationId > 0)
		{
			final List<I_M_Package> result = huShipperTransportationBL.addHUsToShipperTransportation(addToShipperTransportationId, hus);
			mpackagesCreated.addAll(result);
			Loggables.addLog("HUs added to M_ShipperTransportation_ID={}", addToShipperTransportationId);
		}
	}

	private void generateShipments()
	{
		final List<ShipmentScheduleWithHU> candidates = getCandidates();
		if (candidates.isEmpty())
		{
			new AdempiereException("No shipment candidates found")
					.appendParametersToMessage()
					.setParameter("context", this)
					.throwOrLogWarning(failIfNoShipmentCandidatesFound, logger);
			return;
		}

		shipmentsGenerateResult = huShipmentScheduleBL
				.createInOutProducerFromShipmentSchedule()
				.setProcessShipments(completeShipments)
				.setCreatePackingLines(false) // the packing lines shall only be created when the shipments are completed
				.computeShipmentDate(true) // if this is ever used, it should be on true to keep legacy
				// Fail on any exception, because we cannot create just a part of those shipments.
				// Think about HUs which are linked to multiple shipments: you will not see then in Aggregation POS because are already assigned, but u are not able to create shipment from them again.
				.setTrxItemExceptionHandler(FailTrxItemExceptionHandler.instance)
				.createShipments(candidates);
		Loggables.addLog("Generated {}", shipmentsGenerateResult);

	}

	private void generateInvoicesIfNeeded()
	{
		Check.assumeNotNull(shipmentsGenerateResult, "shipments generated");

		final List<I_M_InOut> shipments = shipmentsGenerateResult.getInOuts();
		if (shipments.isEmpty())
		{
			return;
		}

		if (invoiceMode == BillAssociatedInvoiceCandidates.NO)
		{
			return;
		}

		final Set<InvoiceCandidateId> invoiceCandidateIds = invoiceCandDAO.retrieveInvoiceCandidatesQueryForInOuts(shipments)
				.listIds(InvoiceCandidateId::ofRepoId);
		if (invoiceCandidateIds.isEmpty())
		{
			throw new AdempiereException("@NotFound@ @C_Invoice_Candidate_ID@")
					.setParameter("shipments", shipments);
		}

		final PlainInvoicingParams invoicingParams = new PlainInvoicingParams();

		final boolean adhereToInvoiceSchedule = invoiceMode == BillAssociatedInvoiceCandidates.IF_INVOICE_SCHEDULE_PERMITS;
		invoicingParams.setIgnoreInvoiceSchedule(!adhereToInvoiceSchedule);

		final IInvoiceCandidateEnqueueResult enqueueResult = invoiceCandBL.enqueueForInvoicing()
				.setFailOnChanges(false)
				.setInvoicingParams(invoicingParams)
				//
				.enqueueInvoiceCandidateIds(invoiceCandidateIds);
		Loggables.addLog("Invoice candidates enqueued: {}", enqueueResult);
	}

	private void generateShipperDeliveryOrdersIfNeeded()
	{
		if (!createShipperDeliveryOrders)
		{
			return;
		}
		Check.errorIf(addToShipperTransportationId <= 0, "If createShipperDeliveryOrders=true, then addToShipperTransportationId needs to be > 0; this={}", this);

		mpackagesCreated
				.stream()
				.collect(GuavaCollectors.toImmutableListMultimap(mpackage -> extractShipperId(mpackage)))
				.asMap()
				.forEach(this::generateShipperDeliveryOrderIfNeeded);
	}

	private static ShipperId extractShipperId(I_M_Package mpackage)
	{
		return ShipperId.ofRepoId(mpackage.getM_Shipper_ID());
	}

	private void generateShipperDeliveryOrderIfNeeded(
			final ShipperId shipperId,
			@NonNull final Collection<I_M_Package> mpackages)
	{
		final I_M_Shipper shipper = Services.get(IShipperDAO.class).getById(shipperId);
		final String shipperGatewayId = shipper.getShipperGateway();
		if (Check.isEmpty(shipperGatewayId, true))
		{
			return;
		}

		final ShipperGatewayFacade shipperGatewayFacade = shipperGatewayFacadeSupplier.get();
		if (!shipperGatewayFacade.hasServiceSupport(shipperGatewayId))
		{
			return;
		}

		final Set<Integer> mpackageIds = mpackages.stream()
				.map(I_M_Package::getM_Package_ID).collect(ImmutableSet.toImmutableSet());

		final DeliveryOrderCreateRequest request = DeliveryOrderCreateRequest.builder()
				.pickupDate(getShipperDeliveryOrderPickupDate())
				.packageIds(mpackageIds)
				.shipperTransportationId(addToShipperTransportationId)
				.shipperGatewayId(shipperGatewayId)
				.build();
		shipperGatewayFacade.createAndSendDeliveryOrdersForPackages(request);
	}

	public LocalDate getShipperDeliveryOrderPickupDate()
	{
		if (_shipperDeliveryOrderPickupDate == null)
		{
			Check.assume(addToShipperTransportationId > 0, "addToShipperTransportationId > 0");
			final I_M_ShipperTransportation shipperTransportation = load(addToShipperTransportationId, I_M_ShipperTransportation.class);
			_shipperDeliveryOrderPickupDate = TimeUtil.asLocalDate(shipperTransportation.getDateDoc());
		}
		return _shipperDeliveryOrderPickupDate;
	}
}
