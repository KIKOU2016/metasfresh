package de.metas.material.dispo.service.event.handler.pporder;

import static de.metas.material.event.EventTestHelper.NOW;
import static de.metas.material.event.EventTestHelper.createMaterialDescriptor;
import static de.metas.material.event.EventTestHelper.createProductDescriptor;
import static de.metas.material.event.EventTestHelper.createProductDescriptorWithOffSet;
import static java.math.BigDecimal.ONE;
import static java.math.BigDecimal.TEN;
import static java.math.BigDecimal.valueOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.adempiere.warehouse.WarehouseId;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

import de.metas.document.engine.DocStatus;
import de.metas.material.dispo.commons.candidate.Candidate;
import de.metas.material.dispo.commons.candidate.CandidateType;
import de.metas.material.dispo.commons.candidate.businesscase.Flag;
import de.metas.material.dispo.commons.candidate.businesscase.ProductionDetail;
import de.metas.material.dispo.commons.repository.CandidateRepositoryRetrieval;
import de.metas.material.dispo.service.candidatechange.CandidateChangeService;
import de.metas.material.event.commons.EventDescriptor;
import de.metas.material.event.pporder.PPOrder;
import de.metas.material.event.pporder.PPOrderChangedEvent;
import de.metas.material.event.pporder.PPOrderLine;
import de.metas.organization.ClientAndOrgId;
import de.metas.product.ResourceId;
import de.metas.util.time.SystemTime;
import mockit.Expectations;
import mockit.Mocked;
import mockit.Verifications;

/*
 * #%L
 * metasfresh-material-dispo-service
 * %%
 * Copyright (C) 2018 metas GmbH
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

public class PPOrderChangedHandlerTest
{

	@Mocked
	private CandidateChangeService candidateChangeService;

	@Mocked
	private CandidateRepositoryRetrieval candidateRepositoryRetrieval;

	@Test
	public void handleEvent()
	{
		// setup a candidate to be updated
		final Candidate candidateToUpdate = Candidate.builder()
				.clientAndOrgId(ClientAndOrgId.ofClientAndOrg(1, 1))
				// .status(CandidateStatus.doc_closed)
				.type(CandidateType.DEMAND)
				.materialDescriptor(createMaterialDescriptor())
				.businessCaseDetail(ProductionDetail.builder()
						.qty(TEN)
						.advised(Flag.FALSE)
						.pickDirectlyIfFeasible(Flag.FALSE)
						.build())
				.build();

		final PPOrder ppOrder = createPPOrder();
		final int ppOrderId = ppOrder.getPpOrderId();

		// @formatter:off
		new Expectations()
		{{
			candidateRepositoryRetrieval.retrieveCandidatesForPPOrderId(ppOrderId);
			result = ImmutableList.of(candidateToUpdate);
		}};	// @formatter:on

		final PPOrderChangedEvent ppOrderChangedEvent = PPOrderChangedEvent.builder()
				.eventDescriptor(EventDescriptor.ofClientAndOrg(10, 20))
				.oldDocStatus(DocStatus.Completed)
				.newDocStatus(DocStatus.Completed)
				.oldDatePromised(SystemTime.asInstant())
				.newDatePromised(SystemTime.asInstant())
				.newQtyDelivered(ONE)
				.newQtyRequired(TEN)
				.oldQtyDelivered(ONE)
				.oldQtyRequired(TEN)
				// .productDescriptor(materialDescriptor)
				.ppOrderAfterChanges(ppOrder)
				.build();

		final PPOrderChangedHandler ppOrderDocStatusChangedHandler = new PPOrderChangedHandler(
				candidateRepositoryRetrieval,
				candidateChangeService);

		//
		// invoke the method under test
		ppOrderDocStatusChangedHandler.handleEvent(ppOrderChangedEvent);

		//
		// verify the updated candidate created by the handler
		// @formatter:off
		new Verifications()
		{{
			Candidate updatedCandidate;
			candidateChangeService.onCandidateNewOrChange(updatedCandidate = withCapture());

			assertThat(updatedCandidate.getQuantity()).isEqualByComparingTo(BigDecimal.TEN);

			final ProductionDetail productionDetail = ProductionDetail.castOrNull(updatedCandidate.getBusinessCaseDetail());
			assertThat(productionDetail).isNotNull();
			assertThat(productionDetail.getPpOrderDocStatus()).isEqualTo(DocStatus.Completed);
		}};	// @formatter:on
	}

	private PPOrder createPPOrder()
	{
		return PPOrder.builder()
				.ppOrderId(123)
				.clientAndOrgId(ClientAndOrgId.ofClientAndOrg(100, 100))
				.datePromised(NOW)
				.dateStartSchedule(NOW)
				.plantId(ResourceId.ofRepoId(110))
				.productDescriptor(createProductDescriptor())
				.productPlanningId(130)
				.qtyRequired(TEN)
				.qtyDelivered(ONE)
				.warehouseId(WarehouseId.ofRepoId(150))
				.line(PPOrderLine.builder()
						.productDescriptor(createProductDescriptorWithOffSet(20))
						.issueOrReceiveDate(NOW)
						.description("desc2")
						.productBomLineId(380)
						.qtyRequired(valueOf(320))
						.receipt(false)
						.build())
				.build();
	}

}
