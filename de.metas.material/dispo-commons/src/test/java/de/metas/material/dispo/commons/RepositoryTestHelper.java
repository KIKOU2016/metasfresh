package de.metas.material.dispo.commons;

import static de.metas.material.event.EventTestHelper.AFTER_NOW;
import static de.metas.material.event.EventTestHelper.CLIENT_AND_ORG_ID;
import static de.metas.material.event.EventTestHelper.NOW;
import static de.metas.material.event.EventTestHelper.PRODUCT_ID;
import static de.metas.material.event.EventTestHelper.WAREHOUSE_ID;
import static de.metas.material.event.EventTestHelper.createProductDescriptor;

import java.math.BigDecimal;
import java.time.Instant;

import de.metas.material.dispo.commons.candidate.Candidate;
import de.metas.material.dispo.commons.candidate.CandidateType;
import de.metas.material.dispo.commons.repository.CandidateRepositoryRetrieval;
import de.metas.material.dispo.commons.repository.CandidateRepositoryWriteService;
import de.metas.material.dispo.commons.repository.DateAndSeqNo;
import de.metas.material.dispo.commons.repository.DateAndSeqNo.Operator;
import de.metas.material.dispo.commons.repository.atp.AvailableToPromiseMultiQuery;
import de.metas.material.dispo.commons.repository.atp.AvailableToPromiseRepository;
import de.metas.material.dispo.commons.repository.query.CandidatesQuery;
import de.metas.material.dispo.commons.repository.query.MaterialDescriptorQuery;
import de.metas.material.event.commons.MaterialDescriptor;
import lombok.NonNull;
import mockit.Expectations;

/*
 * #%L
 * metasfresh-material-dispo-commons
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

public class RepositoryTestHelper
{
	public final Candidate stockCandidate;
	public final MaterialDescriptor materialDescriptorOfStockCandidate;

	public final Candidate laterStockCandidate;

	public RepositoryTestHelper(@NonNull final CandidateRepositoryWriteService candidateRepositoryCommands)
	{
		materialDescriptorOfStockCandidate = MaterialDescriptor.builder()
				.productDescriptor(createProductDescriptor())
				.warehouseId(WAREHOUSE_ID)
				.quantity(new BigDecimal("11"))
				.date(NOW)
				.build();

		stockCandidate = candidateRepositoryCommands
				.addOrUpdateOverwriteStoredSeqNo(
						Candidate.builder()
								.type(CandidateType.STOCK)
								.clientAndOrgId(CLIENT_AND_ORG_ID)
								.materialDescriptor(materialDescriptorOfStockCandidate)
								.build())
				.getCandidate();

		final MaterialDescriptor laterMaterialDescriptor = MaterialDescriptor.builder()
				.productDescriptor(createProductDescriptor())
				.warehouseId(WAREHOUSE_ID)
				.quantity(new BigDecimal("10"))
				.date(AFTER_NOW)
				.build();

		laterStockCandidate = candidateRepositoryCommands
				.addOrUpdateOverwriteStoredSeqNo(
						Candidate.builder()
								.type(CandidateType.STOCK)
								.clientAndOrgId(CLIENT_AND_ORG_ID)
								.materialDescriptor(laterMaterialDescriptor)
								.build())
				.getCandidate();
	}

	public CandidatesQuery mkQueryForStockUntilDate(@NonNull final Instant date)
	{
		final MaterialDescriptorQuery materialDescriptorQuery = MaterialDescriptorQuery.builder()
				.productId(PRODUCT_ID)
				.warehouseId(WAREHOUSE_ID)
				.timeRangeEnd(DateAndSeqNo.builder()
						.date(date)
						.operator(Operator.INCLUSIVE)
						.build())
				.build();

		return CandidatesQuery.builder()
				.type(CandidateType.STOCK)
				.materialDescriptorQuery(materialDescriptorQuery)
				.build();
	}

	public CandidatesQuery mkQueryForStockFromDate(final Instant date)
	{
		final MaterialDescriptorQuery materialDescriptiorQuery = MaterialDescriptorQuery.builder()
				.productId(PRODUCT_ID)
				.warehouseId(WAREHOUSE_ID)
				.timeRangeStart(DateAndSeqNo.builder()
						.date(date)
						.operator(Operator.INCLUSIVE)
						.build())
				.build();

		return CandidatesQuery.builder()
				.type(CandidateType.STOCK)
				.materialDescriptorQuery(materialDescriptiorQuery)
				.build();
	}

	public static void setupMockedRetrieveAvailableToPromise(
			@NonNull final AvailableToPromiseRepository availableToPromiseRepository,
			@NonNull final MaterialDescriptor materialDescriptor,
			@NonNull final String quantity)
	{
		// @formatter:off
		new Expectations(CandidateRepositoryRetrieval.class)
		{{
			final AvailableToPromiseMultiQuery query = AvailableToPromiseMultiQuery.forDescriptorAndAllPossibleBPartnerIds(materialDescriptor);
			availableToPromiseRepository.retrieveAvailableStockQtySum(query);
			minTimes = 0;
			result = new BigDecimal(quantity);
		}}; // @formatter:on
	}

}
