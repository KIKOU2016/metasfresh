package de.metas.material.dispo.commons.repository.repohelpers;

import static de.metas.material.dispo.commons.candidate.IdConstants.UNSPECIFIED_REPO_ID;
import static de.metas.material.dispo.commons.candidate.IdConstants.toRepoId;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.adempiere.ad.dao.ConstantQueryFilter;
import org.adempiere.ad.dao.ICompositeQueryFilter;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryBuilder;
import org.adempiere.ad.dao.impl.CompareQueryFilter.Operator;
import org.compiere.model.IQuery;
import org.compiere.util.TimeUtil;

import com.google.common.annotations.VisibleForTesting;

import de.metas.material.dispo.commons.candidate.CandidateId;
import de.metas.material.dispo.commons.candidate.TransactionDetail;
import de.metas.material.dispo.commons.repository.DateAndSeqNo;
import de.metas.material.dispo.commons.repository.atp.BPartnerClassifier;
import de.metas.material.dispo.commons.repository.query.CandidatesQuery;
import de.metas.material.dispo.commons.repository.query.DemandDetailsQuery;
import de.metas.material.dispo.commons.repository.query.DistributionDetailsQuery;
import de.metas.material.dispo.commons.repository.query.MaterialDescriptorQuery;
import de.metas.material.dispo.commons.repository.query.MaterialDescriptorQuery.CustomerIdOperator;
import de.metas.material.dispo.commons.repository.query.ProductionDetailsQuery;
import de.metas.material.dispo.model.I_MD_Candidate;
import de.metas.material.dispo.model.I_MD_Candidate_Demand_Detail;
import de.metas.material.dispo.model.I_MD_Candidate_Transaction_Detail;
import de.metas.material.event.commons.AttributesKey;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

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

@UtilityClass
public class RepositoryCommons
{
	/**
	 * Turns the given segment into the "where part" of a big query builder. Does not specify the ordering.
	 */
	public IQueryBuilder<I_MD_Candidate> mkQueryBuilder(@NonNull final CandidatesQuery query)
	{
		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final IQueryBuilder<I_MD_Candidate> builder = queryBL.createQueryBuilder(I_MD_Candidate.class)
				.addOnlyActiveRecordsFilter();

		if (CandidatesQuery.FALSE.equals(query))
		{
			builder.filter(ConstantQueryFilter.of(false));
			return builder;
		}
		else if (!query.getId().isUnspecified())
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_MD_Candidate_ID, query.getId().getRepoId());
			return builder;
		}

		if (query.getType() != null)
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_MD_Candidate_Type, query.getType().toString());
		}

		if (!query.getParentId().isUnspecified())
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_MD_Candidate_Parent_ID, query.getParentId().getRepoId());
		}

		if (query.getGroupId() != null)
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_MD_Candidate_GroupId, query.getGroupId().toInt());
		}

		addMaterialDescriptorToQueryBuilderIfNotNull(
				query.getMaterialDescriptorQuery(),
				query.isMatchExactStorageAttributesKey(),
				builder);

		if (query.getParentMaterialDescriptorQuery() != null)
		{
			final IQueryBuilder<I_MD_Candidate> parentBuilder = queryBL.createQueryBuilder(I_MD_Candidate.class)
					.addOnlyActiveRecordsFilter();

			final boolean atLeastOneFilterAdded = addMaterialDescriptorToQueryBuilderIfNotNull(
					query.getParentMaterialDescriptorQuery(),
					query.isMatchExactStorageAttributesKey(),
					parentBuilder);

			if (atLeastOneFilterAdded)
			{
				// restrict our set of matches to those records that reference a parent record which have the give product and/or warehouse.
				builder.addInSubQueryFilter(I_MD_Candidate.COLUMN_MD_Candidate_Parent_ID, I_MD_Candidate.COLUMN_MD_Candidate_ID, parentBuilder.create());
			}
		}

		if (query.getParentDemandDetailsQuery() != null)
		{
			final IQueryBuilder<I_MD_Candidate> parentBuilder = queryBL.createQueryBuilder(I_MD_Candidate.class)
					.addOnlyActiveRecordsFilter();
			addDemandDetailToBuilder(query.getParentDemandDetailsQuery(), parentBuilder);
			builder.addInSubQueryFilter(I_MD_Candidate.COLUMN_MD_Candidate_Parent_ID, I_MD_Candidate.COLUMN_MD_Candidate_ID, parentBuilder.create());
		}

		if (query.getDemandDetailsQuery() != null)
		{
			addDemandDetailToBuilder(query.getDemandDetailsQuery(), builder);
		}

		addProductionDetailToFilter(query, builder);

		addDistributionDetailToFilter(query, builder);

		PurchaseDetailRepoHelper.addPurchaseDetailsQueryToFilter(query.getPurchaseDetailsQuery(), builder);

		addTransactionDetailToFilter(query, builder);

		return builder;
	}

	@VisibleForTesting
	boolean addMaterialDescriptorToQueryBuilderIfNotNull(
			@Nullable final MaterialDescriptorQuery materialDescriptorQuery,
			final boolean matchExactStorageAttributesKey,
			@NonNull final IQueryBuilder<I_MD_Candidate> builder)
	{
		boolean atLeastOneFilterAdded = false;

		if (materialDescriptorQuery == null)
		{
			return atLeastOneFilterAdded;
		}

		if (materialDescriptorQuery.getWarehouseId() != null)
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_M_Warehouse_ID, materialDescriptorQuery.getWarehouseId());
			atLeastOneFilterAdded = true;
		}
		if (materialDescriptorQuery.getProductId() > 0)
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_M_Product_ID, materialDescriptorQuery.getProductId());
			atLeastOneFilterAdded = true;
		}

		final BPartnerClassifier customer = materialDescriptorQuery.getCustomer();
		if (customer.isSpecificBPartner())
		{
			final CustomerIdOperator customerIdOperator = materialDescriptorQuery.getCustomerIdOperator();
			if (CustomerIdOperator.GIVEN_ID_ONLY.equals(customerIdOperator))
			{
				builder.addEqualsFilter(I_MD_Candidate.COLUMN_C_BPartner_Customer_ID, customer.getBpartnerId());
			}
			else if (CustomerIdOperator.GIVEN_ID_OR_NULL.equals(customerIdOperator))
			{
				builder.addInArrayFilter(I_MD_Candidate.COLUMN_C_BPartner_Customer_ID, customer.getBpartnerId(), null);
			}
			atLeastOneFilterAdded = true;
		}
		else if (customer.isNone())
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_C_BPartner_Customer_ID, null);
			atLeastOneFilterAdded = true;
		}

		if (!Objects.equals(materialDescriptorQuery.getStorageAttributesKey(), AttributesKey.ALL))
		{
			final AttributesKey attributesKey = materialDescriptorQuery.getStorageAttributesKey();
			if (matchExactStorageAttributesKey)
			{
				builder.addEqualsFilter(I_MD_Candidate.COLUMN_StorageAttributesKey, attributesKey.getAsString());
			}
			else
			{
				builder.addStringLikeFilter(I_MD_Candidate.COLUMN_StorageAttributesKey, attributesKey.getSqlLikeString(), false); // iggnoreCase=false
			}
			atLeastOneFilterAdded = true;
		}

		atLeastOneFilterAdded = configureBuilderDateFilters(materialDescriptorQuery, builder) || atLeastOneFilterAdded;

		return atLeastOneFilterAdded;
	}

	private boolean configureBuilderDateFilters(
			@NonNull final MaterialDescriptorQuery materialDescriptorQuery,
			@NonNull final IQueryBuilder<I_MD_Candidate> builder)
	{
		boolean atLeastOneFilterAdded = false;

		final DateAndSeqNo atTime = materialDescriptorQuery.getAtTime();
		if (atTime != null)
		{
			builder.addEqualsFilter(I_MD_Candidate.COLUMN_DateProjected, TimeUtil.asTimestamp(atTime.getDate()));
			if (atTime.getSeqNo() > 0)
			{
				builder.addEqualsFilter(I_MD_Candidate.COLUMN_SeqNo, atTime.getSeqNo());
			}
			atLeastOneFilterAdded = true;
		}
		final DateAndSeqNo timeRangeStart = materialDescriptorQuery.getTimeRangeStart();
		if (timeRangeStart != null)
		{
			switch (timeRangeStart.getOperator())
			{
				case INCLUSIVE:
					addDateAndSeqNoToBuilder(builder, Operator.GREATER_OR_EQUAL, timeRangeStart);
					break;
				case EXCLUSIVE:
					addDateAndSeqNoToBuilder(builder, Operator.GREATER, timeRangeStart);
					break;
				default:
					Check.fail("timeRangeStart has a unexpected dateOperator {}; query={}", timeRangeStart.getOperator(), materialDescriptorQuery);
					break;
			}
			atLeastOneFilterAdded = true;
		}

		final DateAndSeqNo timeRangeEnd = materialDescriptorQuery.getTimeRangeEnd();
		if (timeRangeEnd != null)
		{
			switch (timeRangeEnd.getOperator())
			{
				case INCLUSIVE:
					addDateAndSeqNoToBuilder(builder, Operator.LESS_OR_EQUAL, timeRangeEnd);
					break;
				case EXCLUSIVE:
					addDateAndSeqNoToBuilder(builder, Operator.LESS, timeRangeEnd);
					break;
				default:
					Check.fail("timeRangeEnd has a unexpected dateOperator {}; query={}", timeRangeEnd.getOperator(), materialDescriptorQuery);
					break;
			}
			atLeastOneFilterAdded = true;
		}

		return atLeastOneFilterAdded;
	}

	private void addDateAndSeqNoToBuilder(
			@NonNull final IQueryBuilder<I_MD_Candidate> builder,
			@NonNull final Operator operator,
			@NonNull final DateAndSeqNo timeRangeItem)
	{
		final Timestamp timestamp = TimeUtil.asTimestamp(timeRangeItem.getDate());

		if (timeRangeItem.getSeqNo() > 0)
		{
			// e.g. creates a filter such as "( date <= .. OR ( date = .. AND seqNo <= .. ) )"
			final IQueryBL queryBL = Services.get(IQueryBL.class);
			final ICompositeQueryFilter<I_MD_Candidate> orFilter = queryBL
					.createCompositeQueryFilter(I_MD_Candidate.class)
					.setJoinOr();

			orFilter.addCompareFilter(I_MD_Candidate.COLUMN_DateProjected, operator, timestamp);

			final ICompositeQueryFilter<I_MD_Candidate> andSubFilter = queryBL.createCompositeQueryFilter(I_MD_Candidate.class);
			andSubFilter.addEqualsFilter(I_MD_Candidate.COLUMN_DateProjected, timestamp);
			andSubFilter.addCompareFilter(I_MD_Candidate.COLUMN_SeqNo, operator, timeRangeItem.getSeqNo());
			orFilter.addFilter(andSubFilter);

			builder.filter(orFilter);
		}
		else
		{
			builder.addCompareFilter(I_MD_Candidate.COLUMN_DateProjected, operator, timestamp);
		}
	}

	/**
	 * filter by demand detail, ignore if there is none!
	 *
	 * @param candidate
	 * @param builder
	 */
	private void addDemandDetailToBuilder(
			@Nullable final DemandDetailsQuery demandDetailsQuery,
			@NonNull final IQueryBuilder<I_MD_Candidate> builder)
	{
		if (demandDetailsQuery == null)
		{
			return;
		}

		final IQueryBuilder<I_MD_Candidate_Demand_Detail> demandDetailsSubQueryBuilder = Services.get(IQueryBL.class)
				.createQueryBuilder(I_MD_Candidate_Demand_Detail.class)
				.addOnlyActiveRecordsFilter();

		final boolean hasOrderLine = demandDetailsQuery.getOrderLineId() != UNSPECIFIED_REPO_ID;
		if (hasOrderLine)
		{
			demandDetailsSubQueryBuilder
					.addEqualsFilter(I_MD_Candidate_Demand_Detail.COLUMN_C_OrderLine_ID, toRepoId(demandDetailsQuery.getOrderLineId()));
		}

		final boolean hasSubscriptionLine = demandDetailsQuery.getSubscriptionLineId() != UNSPECIFIED_REPO_ID;
		if (hasSubscriptionLine)
		{
			demandDetailsSubQueryBuilder
					.addEqualsFilter(I_MD_Candidate_Demand_Detail.COLUMN_C_SubscriptionProgress_ID, toRepoId(demandDetailsQuery.getSubscriptionLineId()));
		}

		final boolean hasShipmentschedule = demandDetailsQuery.getShipmentScheduleId() != UNSPECIFIED_REPO_ID;
		if (hasShipmentschedule)
		{
			demandDetailsSubQueryBuilder
					.addEqualsFilter(I_MD_Candidate_Demand_Detail.COLUMNNAME_M_ShipmentSchedule_ID, toRepoId(demandDetailsQuery.getShipmentScheduleId()));
		}

		final boolean hasForecastLine = demandDetailsQuery.getForecastLineId() != UNSPECIFIED_REPO_ID;
		if (hasForecastLine)
		{
			demandDetailsSubQueryBuilder
					.addEqualsFilter(I_MD_Candidate_Demand_Detail.COLUMN_M_ForecastLine_ID, toRepoId(demandDetailsQuery.getForecastLineId()));
		}

		if (hasOrderLine || hasForecastLine || hasShipmentschedule)
		{
			builder.addInSubQueryFilter(I_MD_Candidate.COLUMN_MD_Candidate_ID,
					I_MD_Candidate_Demand_Detail.COLUMN_MD_Candidate_ID,
					demandDetailsSubQueryBuilder.create());
		}
	}

	private void addProductionDetailToFilter(
			final CandidatesQuery candidate,
			final IQueryBuilder<I_MD_Candidate> builder)
	{
		final ProductionDetailsQuery productionDetailsQuery = candidate.getProductionDetailsQuery();
		if (productionDetailsQuery != null)
		{
			productionDetailsQuery.augmentQueryBuilder(builder);
		}
	}

	private void addDistributionDetailToFilter(
			@NonNull final CandidatesQuery query,
			@NonNull final IQueryBuilder<I_MD_Candidate> builder)
	{
		final DistributionDetailsQuery distributionDetail = query.getDistributionDetailsQuery();
		if (distributionDetail != null)
		{
			distributionDetail.augmentQueryBuilder(builder);
		}
	}

	private void addTransactionDetailToFilter(
			@NonNull final CandidatesQuery query,
			@NonNull final IQueryBuilder<I_MD_Candidate> builder)
	{
		final List<TransactionDetail> transactionDetails = query.getTransactionDetails();
		if (transactionDetails == null || transactionDetails.isEmpty())
		{
			return;
		}

		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final IQueryBuilder<I_MD_Candidate_Transaction_Detail> transactionDetailSubQueryBuilder = queryBL
				.createQueryBuilder(I_MD_Candidate_Transaction_Detail.class)
				.addOnlyActiveRecordsFilter();

		for (final TransactionDetail transactionDetail : transactionDetails)
		{
			if (transactionDetail.getTransactionId() > 0)
			{
				transactionDetailSubQueryBuilder.addEqualsFilter(I_MD_Candidate_Transaction_Detail.COLUMN_M_Transaction_ID, transactionDetail.getTransactionId());
			}
			if (transactionDetail.getResetStockAdPinstanceId() > 0)
			{
				transactionDetailSubQueryBuilder.addEqualsFilter(I_MD_Candidate_Transaction_Detail.COLUMN_AD_PInstance_ResetStock_ID, transactionDetail.getResetStockAdPinstanceId());
			}

			if (transactionDetail.getQuantity() != null)
			{
				transactionDetailSubQueryBuilder.addEqualsFilter(I_MD_Candidate_Transaction_Detail.COLUMN_MovementQty, transactionDetail.getQuantity());
			}
			builder.addInSubQueryFilter(I_MD_Candidate.COLUMN_MD_Candidate_ID, I_MD_Candidate_Transaction_Detail.COLUMN_MD_Candidate_ID, transactionDetailSubQueryBuilder.create());
		}
	}

	public CandidateId candidateIdOf(@NonNull final I_MD_Candidate candidateRecord)
	{
		return CandidateId.ofRepoId(candidateRecord.getMD_Candidate_ID());
	}

	public <T> T retrieveSingleCandidateDetail(
			@NonNull final I_MD_Candidate candidateRecord,
			@NonNull final Class<T> modelClass)
	{
		final IQuery<T> candidateDetailQueryBuilder = createCandidateDetailQueryBuilder(candidateRecord, modelClass);
		final T existingDetail = candidateDetailQueryBuilder
				.firstOnly(modelClass);
		return existingDetail;
	}

	public <T> IQuery<T> createCandidateDetailQueryBuilder(
			@NonNull final I_MD_Candidate candidateRecord,
			@NonNull final Class<T> modelClass)
	{
		final IQueryBL queryBL = Services.get(IQueryBL.class);
		final IQuery<T> candidateDetailQueryBuilder = queryBL.createQueryBuilder(modelClass)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_MD_Candidate.COLUMNNAME_MD_Candidate_ID, candidateRecord.getMD_Candidate_ID())
				.create();
		return candidateDetailQueryBuilder;
	}

}
