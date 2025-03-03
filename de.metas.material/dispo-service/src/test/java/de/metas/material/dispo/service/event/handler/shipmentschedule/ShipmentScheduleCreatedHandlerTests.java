package de.metas.material.dispo.service.event.handler.shipmentschedule;

import static de.metas.material.event.EventTestHelper.BPARTNER_ID;
import static de.metas.material.event.EventTestHelper.CLIENT_AND_ORG_ID;
import static de.metas.material.event.EventTestHelper.NOW;
import static de.metas.material.event.EventTestHelper.createProductDescriptor;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.adempiere.ad.wrapper.POJOLookupMap;
import org.adempiere.test.AdempiereTestHelper;
import org.adempiere.test.AdempiereTestWatcher;
import org.adempiere.warehouse.WarehouseId;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;

import com.google.common.collect.ImmutableList;

import de.metas.material.dispo.commons.DispoTestUtils;
import de.metas.material.dispo.commons.RepositoryTestHelper;
import de.metas.material.dispo.commons.candidate.CandidateType;
import de.metas.material.dispo.commons.repository.CandidateRepositoryRetrieval;
import de.metas.material.dispo.commons.repository.CandidateRepositoryWriteService;
import de.metas.material.dispo.commons.repository.atp.AvailableToPromiseRepository;
import de.metas.material.dispo.model.I_MD_Candidate;
import de.metas.material.dispo.model.I_MD_Candidate_Demand_Detail;
import de.metas.material.dispo.service.candidatechange.CandidateChangeService;
import de.metas.material.dispo.service.candidatechange.StockCandidateService;
import de.metas.material.dispo.service.candidatechange.handler.DemandCandiateHandler;
import de.metas.material.event.PostMaterialEventService;
import de.metas.material.event.commons.EventDescriptor;
import de.metas.material.event.commons.MaterialDescriptor;
import de.metas.material.event.commons.OrderLineDescriptor;
import de.metas.material.event.shipmentschedule.ShipmentScheduleCreatedEvent;
import lombok.NonNull;
import mockit.Mocked;

/*
 * #%L
 * metasfresh-material-dispo-service
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

public class ShipmentScheduleCreatedHandlerTests
{
	private static final int shipmentScheduleId = 76;

	private static final int orderLineId = 86;

	/** Watches the current tests and dumps the database to console in case of failure */
	@Rule
	public final TestWatcher testWatcher = new AdempiereTestWatcher();

	private static final WarehouseId toWarehouseId = WarehouseId.ofRepoId(30);

	@Mocked
	private PostMaterialEventService postMaterialEventService;

	private ShipmentScheduleCreatedHandler shipmentScheduleCreatedHandler;

	private AvailableToPromiseRepository atpRepository;

	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();

		final CandidateRepositoryRetrieval candidateRepositoryRetrieval = new CandidateRepositoryRetrieval();

		final CandidateRepositoryWriteService candidateRepositoryCommands = new CandidateRepositoryWriteService();

		atpRepository = new AvailableToPromiseRepository();

		final CandidateChangeService candidateChangeHandler = new CandidateChangeService(ImmutableList.of(
				new DemandCandiateHandler(
						candidateRepositoryRetrieval,
						candidateRepositoryCommands,
						postMaterialEventService,
						atpRepository,
						new StockCandidateService(
								candidateRepositoryRetrieval,
								candidateRepositoryCommands))));

		shipmentScheduleCreatedHandler = new ShipmentScheduleCreatedHandler(
				candidateChangeHandler,
				candidateRepositoryRetrieval);
	}

	@Test
	public void handleEvent()
	{
		performTest(shipmentScheduleCreatedHandler, atpRepository);
	}

	public static int performTest(
			@NonNull final ShipmentScheduleCreatedHandler shipmentScheduleEventHandler,
			@NonNull final AvailableToPromiseRepository atpRepository)
	{
		final ShipmentScheduleCreatedEvent event = createShipmentScheduleTestEvent();

		RepositoryTestHelper.setupMockedRetrieveAvailableToPromise(
				atpRepository,
				event.getMaterialDescriptor(),
				"0"); // ATP-quantity = 0

		shipmentScheduleEventHandler.handleEvent(event);

		final List<I_MD_Candidate> allRecords = DispoTestUtils.retrieveAllRecords();
		assertThat(allRecords).hasSize(2);

		assertThat(DispoTestUtils.filter(CandidateType.DEMAND)).hasSize(1);
		assertThat(DispoTestUtils.filter(CandidateType.STOCK)).hasSize(1);

		final I_MD_Candidate demandRecord = DispoTestUtils.filter(CandidateType.DEMAND).get(0);
		final I_MD_Candidate stockRecord = DispoTestUtils.filter(CandidateType.STOCK).get(0);

		assertThat(demandRecord.getSeqNo()).isEqualTo(stockRecord.getSeqNo());
		assertThat(stockRecord.getMD_Candidate_Parent_ID()).isEqualTo(demandRecord.getMD_Candidate_ID());

		assertThat(demandRecord.getQty()).isEqualByComparingTo("10");
		assertThat(stockRecord.getQty()).isEqualByComparingTo("-10"); // the stock is unbalanced, because there is no existing stock and no supply

		assertThat(allRecords).allSatisfy(r -> assertThat(r.getC_BPartner_Customer_ID()).isEqualTo(BPARTNER_ID.getRepoId()));

		final List<I_MD_Candidate_Demand_Detail> demandDetailRecords = POJOLookupMap.get().getRecords(I_MD_Candidate_Demand_Detail.class);
		assertThat(demandDetailRecords).hasSize(1);
		assertThat(demandDetailRecords.get(0).getM_ShipmentSchedule_ID()).isEqualTo(event.getShipmentScheduleId());

		return event.getShipmentScheduleId();
	}

	public static ShipmentScheduleCreatedEvent createShipmentScheduleTestEvent()
	{
		final ShipmentScheduleCreatedEvent event = ShipmentScheduleCreatedEvent.builder()
				.eventDescriptor(EventDescriptor.ofClientAndOrg(CLIENT_AND_ORG_ID))
				.materialDescriptor(MaterialDescriptor.builder()
						.date(NOW)
						.productDescriptor(createProductDescriptor())
						.customerId(BPARTNER_ID)
						.quantity(BigDecimal.TEN)
						.warehouseId(toWarehouseId)
						.build())
				.reservedQuantity(new BigDecimal("20"))
				.shipmentScheduleId(shipmentScheduleId)
				.documentLineDescriptor(OrderLineDescriptor.builder()
						.orderLineId(orderLineId)
						.orderId(30)
						.build())
				.build();
		return event;
	}
}
