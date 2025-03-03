package de.metas.pricing.conditions.service.impl;

import static org.adempiere.model.InterfaceWrapperHelper.copy;
import static org.adempiere.model.InterfaceWrapperHelper.load;
import static org.adempiere.model.InterfaceWrapperHelper.loadOutOfTrx;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.saveRecord;

import java.math.BigDecimal;
import java.util.Collection;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.adempiere.ad.dao.ICompositeQueryFilter;
import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryFilter;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.mm.attributes.AttributeId;
import org.adempiere.mm.attributes.AttributeValueId;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ClientId;
import org.compiere.SpringContextHolder;
import org.compiere.model.IQuery;
import org.compiere.model.I_M_DiscountSchema;
import org.compiere.model.I_M_DiscountSchemaBreak;
import org.compiere.model.I_M_DiscountSchemaLine;
import org.compiere.model.X_M_DiscountSchemaBreak;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;

import ch.qos.logback.classic.Level;
import de.metas.bpartner.BPartnerId;
import de.metas.cache.CCache;
import de.metas.currency.ICurrencyBL;
import de.metas.logging.LogManager;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.organization.OrgId;
import de.metas.payment.paymentterm.PaymentTermId;
import de.metas.payment.paymentterm.PaymentTermService;
import de.metas.pricing.PricingSystemId;
import de.metas.pricing.conditions.BreakValueType;
import de.metas.pricing.conditions.PriceSpecification;
import de.metas.pricing.conditions.PriceSpecificationType;
import de.metas.pricing.conditions.PricingConditions;
import de.metas.pricing.conditions.PricingConditionsBreak;
import de.metas.pricing.conditions.PricingConditionsBreakId;
import de.metas.pricing.conditions.PricingConditionsBreakMatchCriteria;
import de.metas.pricing.conditions.PricingConditionsDiscountType;
import de.metas.pricing.conditions.PricingConditionsId;
import de.metas.pricing.conditions.service.IPricingConditionsRepository;
import de.metas.pricing.conditions.service.PricingConditionsBreakChangeRequest;
import de.metas.product.ProductCategoryId;
import de.metas.product.ProductId;
import de.metas.user.UserId;
import de.metas.util.Check;
import de.metas.util.GuavaCollectors;
import de.metas.util.Loggables;
import de.metas.util.Services;
import de.metas.util.lang.Percent;
import lombok.NonNull;

public class PricingConditionsRepository implements IPricingConditionsRepository
{
	private static final Logger logger = LogManager.getLogger(PricingConditionsRepository.class);

	private final CCache<PricingConditionsId, PricingConditions> pricingConditionsById = CCache.<PricingConditionsId, PricingConditions> builder()
			.tableName(I_M_DiscountSchema.Table_Name)
			.initialCapacity(10)
			.additionalTableNameToResetFor(I_M_DiscountSchemaBreak.Table_Name)
			.build();

	@Override
	public PricingConditions getPricingConditionsById(@NonNull final PricingConditionsId pricingConditionsId)
	{
		return pricingConditionsById.getOrLoad(pricingConditionsId, this::retrievePricingConditionsById);
	}

	@Override
	public Collection<PricingConditions> getPricingConditionsByIds(final Collection<PricingConditionsId> pricingConditionIds)
	{
		return pricingConditionsById.getAllOrLoad(pricingConditionIds, this::retrievePricingConditionsByIds);
	}

	@VisibleForTesting
	PricingConditions retrievePricingConditionsById(@NonNull final PricingConditionsId id)
	{
		final int discountSchemaRecordId = id.getDiscountSchemaId();

		final PricingConditionsId pricingConditionsId = PricingConditionsId.ofDiscountSchemaId(discountSchemaRecordId);
		final I_M_DiscountSchema discountSchemaRecord = loadOutOfTrx(discountSchemaRecordId, I_M_DiscountSchema.class);
		final List<I_M_DiscountSchemaBreak> schemaBreakRecords = streamSchemaBreakRecords(ImmutableList.of(pricingConditionsId))
				.collect(ImmutableList.toImmutableList());

		return toPricingConditions(discountSchemaRecord, schemaBreakRecords);
	}

	private Map<PricingConditionsId, PricingConditions> retrievePricingConditionsByIds(final Collection<PricingConditionsId> ids)
	{
		if (ids.isEmpty())
		{
			return ImmutableMap.of();
		}

		final Set<Integer> discountSchemaIds = PricingConditionsId.toDiscountSchemaIds(ids);

		final ListMultimap<Integer, I_M_DiscountSchemaBreak> schemaBreakRecords = streamSchemaBreakRecords(ids)
				.collect(GuavaCollectors.toImmutableListMultimap(I_M_DiscountSchemaBreak::getM_DiscountSchema_ID));

		return Services.get(IQueryBL.class).createQueryBuilderOutOfTrx(I_M_DiscountSchema.class)
				.addInArrayFilter(I_M_DiscountSchema.COLUMNNAME_M_DiscountSchema_ID, discountSchemaIds)
				.create()
				.stream()
				.map(discountSchema -> toPricingConditions(discountSchema, schemaBreakRecords.get(discountSchema.getM_DiscountSchema_ID())))
				.collect(GuavaCollectors.toImmutableMapByKey(PricingConditions::getId));
	}

	private static PricingConditions toPricingConditions(final I_M_DiscountSchema discountSchemaRecord, final List<I_M_DiscountSchemaBreak> schemaBreakRecords)
	{
		final PricingConditionsDiscountType discountType = PricingConditionsDiscountType.forCode(discountSchemaRecord.getDiscountType());
		final List<PricingConditionsBreak> breaks;
		final BreakValueType breakValueType;
		AttributeId breakAttributeId = null;
		if (discountType == PricingConditionsDiscountType.BREAKS)
		{
			breakValueType = BreakValueType.forCode(discountSchemaRecord.getBreakValueType());
			if (breakValueType == BreakValueType.ATTRIBUTE)
			{
				breakAttributeId = AttributeId.ofRepoId(discountSchemaRecord.getBreakValue_Attribute_ID());
			}

			breaks = schemaBreakRecords.stream()
					.filter(I_M_DiscountSchemaBreak::isActive)
					.filter(I_M_DiscountSchemaBreak::isValid)
					.map(schemaBreakRecord -> toPricingConditionsBreak(schemaBreakRecord))
					.collect(ImmutableList.toImmutableList());
		}
		else
		{
			breakValueType = null;
			breaks = ImmutableList.of();
		}

		return PricingConditions.builder()
				.id(PricingConditionsId.ofDiscountSchemaId(discountSchemaRecord.getM_DiscountSchema_ID()))
				.discountType(discountType)
				.bpartnerFlatDiscount(discountSchemaRecord.isBPartnerFlatDiscount())
				.flatDiscount(Percent.of(discountSchemaRecord.getFlatDiscount()))
				.breakValueType(breakValueType)
				.breakAttributeId(breakAttributeId)
				.breaks(breaks)
				.build();
	}

	public static PricingConditionsBreak toPricingConditionsBreak(@NonNull final I_M_DiscountSchemaBreak schemaBreakRecord)
	{
		final int discountSchemaBreakId = schemaBreakRecord.getM_DiscountSchemaBreak_ID();
		final PricingConditionsBreakId id = discountSchemaBreakId > 0 ? PricingConditionsBreakId.of(schemaBreakRecord.getM_DiscountSchema_ID(), discountSchemaBreakId) : null;

		final PaymentTermId paymentTermIdOrNull = PaymentTermId.ofRepoIdOrNull(schemaBreakRecord.getC_PaymentTerm_ID());

		final Percent paymentDiscount = Percent.ofNullable(schemaBreakRecord.getPaymentDiscount());

		final PaymentTermService paymentTermService = SpringContextHolder.instance.getBean(PaymentTermService.class);
		final PaymentTermId derivedPaymentTermId = paymentTermService.getOrCreateDerivedPaymentTerm(
				paymentTermIdOrNull,
				paymentDiscount);

		return PricingConditionsBreak.builder()
				.id(id)
				.matchCriteria(toPricingConditionsBreakMatchCriteria(schemaBreakRecord))
				.seqNo(schemaBreakRecord.getSeqNo())
				//
				.priceSpecification(toPriceSpecification(schemaBreakRecord))
				//
				.bpartnerFlatDiscount(schemaBreakRecord.isBPartnerFlatDiscount())
				.discount(Percent.of(schemaBreakRecord.getBreakDiscount()))
				//
				.paymentTermIdOrNull(paymentTermIdOrNull)
				.paymentDiscountOverrideOrNull(paymentDiscount)
				.derivedPaymentTermIdOrNull(derivedPaymentTermId)
				//
				.qualityDiscountPercentage(schemaBreakRecord.getQualityIssuePercentage())
				//
				//
				.dateCreated(TimeUtil.asInstant(schemaBreakRecord.getCreated()))
				.createdById(UserId.ofRepoIdOrNull(schemaBreakRecord.getCreatedBy()))
				.hasChanges(false)
				.build();
	}

	@VisibleForTesting
	static PricingConditionsBreakMatchCriteria toPricingConditionsBreakMatchCriteria(final I_M_DiscountSchemaBreak schemaBreakRecord)
	{
		return PricingConditionsBreakMatchCriteria.builder()
				.breakValue(schemaBreakRecord.getBreakValue())
				.productId(ProductId.ofRepoIdOrNull(schemaBreakRecord.getM_Product_ID()))
				.productCategoryId(ProductCategoryId.ofRepoIdOrNull(schemaBreakRecord.getM_Product_Category_ID()))
				.productManufacturerId(BPartnerId.ofRepoIdOrNull(schemaBreakRecord.getManufacturer_ID()))
				.attributeValueId(AttributeValueId.ofRepoIdOrNull(schemaBreakRecord.getM_AttributeValue_ID()))
				.build();
	}

	private static PriceSpecification toPriceSpecification(@NonNull final I_M_DiscountSchemaBreak discountSchemaBreakRecord)
	{
		final String priceBase = discountSchemaBreakRecord.getPriceBase();

		if (Check.isEmpty(priceBase, true))
		{
			return PriceSpecification.none();
		}
		else if (X_M_DiscountSchemaBreak.PRICEBASE_PricingSystem.equals(priceBase))
		{
			final int basePricingSystemRepoId = discountSchemaBreakRecord.getBase_PricingSystem_ID();
			final PricingSystemId basePricingSystemId = PricingSystemId.ofRepoIdOrNull(basePricingSystemRepoId);
			if (basePricingSystemId == null)
			{
				Loggables.withLogger(logger, Level.WARN).addLog(
						"Ignoring M_DiscountSchemaBreak_ID={} of M_DiscountSchema_ID={} which has PriceBase=P(ricingSystem), but Base_PricingSystem_ID={}",
						discountSchemaBreakRecord.getM_DiscountSchemaBreak_ID(), discountSchemaBreakRecord.getM_DiscountSchema_ID(), basePricingSystemRepoId);
				return PriceSpecification.none();
			}

			final BigDecimal surchargeAmt = discountSchemaBreakRecord.getPricingSystemSurchargeAmt();
			if (surchargeAmt == null || surchargeAmt.signum() == 0)
			{
				return PriceSpecification.basePricingSystem(basePricingSystemId);
			}
			else
			{
				final CurrencyId currencyId = extractCurrencyId(discountSchemaBreakRecord);
				final Money surcharge = Money.of(surchargeAmt, currencyId);
				return PriceSpecification.basePricingSystem(basePricingSystemId, surcharge);
			}
		}
		else if (X_M_DiscountSchemaBreak.PRICEBASE_Fixed.equals(priceBase))
		{
			final CurrencyId currencyId = extractCurrencyId(discountSchemaBreakRecord);
			final Money fixedPrice = Money.of(discountSchemaBreakRecord.getPriceStdFixed(), currencyId);
			return PriceSpecification.fixedPrice(fixedPrice);
		}
		else
		{
			throw new AdempiereException("Unknown PriceBase: " + priceBase);
		}
	}

	private static CurrencyId extractCurrencyId(final I_M_DiscountSchemaBreak discountSchemaBreakRecord)
	{
		final int currencyRepoId = discountSchemaBreakRecord.getC_Currency_ID();
		final CurrencyId currencyId = CurrencyId.ofRepoIdOrNull(currencyRepoId);
		if (currencyId != null)
		{
			return currencyId;
		}

		// Fallback: use default currency
		return Services.get(ICurrencyBL.class).getBaseCurrencyId(
				ClientId.ofRepoId(discountSchemaBreakRecord.getAD_Client_ID()),
				OrgId.ofRepoIdOrAny(discountSchemaBreakRecord.getAD_Org_ID()));
	}

	@VisibleForTesting
	/* package */ Stream<I_M_DiscountSchemaBreak> streamSchemaBreakRecords(final Collection<PricingConditionsId> pricingConditionsId)
	{
		if (pricingConditionsId.isEmpty())
		{
			return Stream.empty();
		}

		final ImmutableList<Integer> discountSchemaRecordIds = pricingConditionsId.stream()
				.map(PricingConditionsId::getDiscountSchemaId)
				.collect(ImmutableList.toImmutableList());

		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_M_DiscountSchemaBreak.class)
				.addOnlyActiveRecordsFilter()
				.addInArrayFilter(I_M_DiscountSchemaBreak.COLUMNNAME_M_DiscountSchema_ID, discountSchemaRecordIds)
				.orderBy(I_M_DiscountSchemaBreak.COLUMNNAME_M_DiscountSchema_ID)
				.orderBy(I_M_DiscountSchemaBreak.COLUMNNAME_SeqNo)
				.orderBy(I_M_DiscountSchemaBreak.COLUMNNAME_M_DiscountSchemaBreak_ID)
				.create()
				.stream();
	}

	@VisibleForTesting
	/* package */ List<I_M_DiscountSchemaLine> retrieveLines(final int discountSchemaId)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_M_DiscountSchemaLine.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_M_DiscountSchemaLine.COLUMNNAME_M_DiscountSchema_ID, discountSchemaId)
				.orderBy(I_M_DiscountSchemaLine.COLUMNNAME_SeqNo)
				.orderBy(I_M_DiscountSchemaLine.COLUMNNAME_M_DiscountSchemaLine_ID)
				.create()
				.listImmutable(I_M_DiscountSchemaLine.class);
	}

	@Override
	public int resequence(final int discountSchemaId)
	{
		final int countLines = resequenceLines(discountSchemaId);
		final int countBreaks = resequenceBreaks(discountSchemaId);
		return countLines + countBreaks;
	}

	private int resequenceLines(final int discountSchemaId)
	{
		int countUpdated = 0;

		final List<I_M_DiscountSchemaLine> lines = retrieveLines(discountSchemaId);
		int i = 0;
		for (final I_M_DiscountSchemaLine currentLine : lines)
		{
			final int currentSeq = (i + 1) * 10;
			if (currentSeq != currentLine.getSeqNo())
			{
				currentLine.setSeqNo(currentSeq);
				InterfaceWrapperHelper.save(currentLine);
				countUpdated++;
			}
			i++;
		}

		return countUpdated;
	}

	private int resequenceBreaks(final int discountSchemaId)
	{
		int countUpdated = 0;

		final PricingConditionsId pricingConditionsId = PricingConditionsId.ofDiscountSchemaId(discountSchemaId);

		final List<I_M_DiscountSchemaBreak> breaks = streamSchemaBreakRecords(ImmutableList.of(pricingConditionsId))
				.collect(ImmutableList.toImmutableList());
		int i = 0;
		for (final I_M_DiscountSchemaBreak br : breaks)
		{
			final int currentSeq = (i + 1) * 10;
			if (currentSeq != br.getSeqNo())
			{
				br.setSeqNo(currentSeq);
				InterfaceWrapperHelper.save(br);
				countUpdated++;
			}
			i++;
		}

		return countUpdated;
	}

	@Override
	public PricingConditionsBreak changePricingConditionsBreak(@NonNull final PricingConditionsBreakChangeRequest request)
	{
		final ITrxManager trxManager = Services.get(ITrxManager.class);
		return trxManager.call(ITrx.TRXNAME_ThreadInherited, () -> changePricingConditionsBreak0(request));
	}

	private PricingConditionsBreak changePricingConditionsBreak0(@NonNull final PricingConditionsBreakChangeRequest request)
	{
		final PricingConditionsId pricingConditionsId = request.getPricingConditionsId();

		//
		// Load/Create discount schema record
		final I_M_DiscountSchemaBreak schemaBreak;
		final PricingConditionsBreakId pricingConditionsBreakId = request.getPricingConditionsBreakId();
		if (pricingConditionsBreakId != null)
		{
			schemaBreak = load(pricingConditionsBreakId.getDiscountSchemaBreakId(), I_M_DiscountSchemaBreak.class);
			if (!pricingConditionsBreakId.matchingDiscountSchemaId(schemaBreak.getM_DiscountSchema_ID()))
			{
				throw new AdempiereException("" + request + " and " + schemaBreak + " does not have the same discount schema");
			}
		}
		else
		{
			if (pricingConditionsId == null)
			{
				throw new AdempiereException("Cannot create new break because no pricingConditionsId found: " + request);
			}
			final int discountSchemaId = pricingConditionsId.getDiscountSchemaId();

			schemaBreak = newInstance(I_M_DiscountSchemaBreak.class);
			schemaBreak.setM_DiscountSchema_ID(discountSchemaId);
			schemaBreak.setSeqNo(retrieveNextSeqNo(discountSchemaId));
			schemaBreak.setBreakValue(BigDecimal.ZERO);
		}

		//
		// Update
		updateSchemaBreakRecordFromRecordFromMatchCriteria(schemaBreak, request.getMatchCriteria());
		updateSchemaBreakRecordFromSourceScheamaBreakRecord(schemaBreak, request.getUpdateFromPricingConditionsBreakId());
		updateSchemaBreakRecordFromPrice(schemaBreak, request.getPrice());
		if (request.getDiscount() != null)
		{
			schemaBreak.setBreakDiscount(request.getDiscount().toBigDecimal());
		}

		if (request.getPaymentTermId() != null)
		{
			final int paymentTermRepoId = PaymentTermId.toRepoId(request.getPaymentTermId().orElse(null));
			schemaBreak.setC_PaymentTerm_ID(paymentTermRepoId);
		}
		if (request.getPaymentDiscount() != null)
		{
			final BigDecimal paymentDiscountValue = request
					.getPaymentDiscount()
					.map(Percent::toBigDecimal)
					.orElse(null);
			schemaBreak.setPaymentDiscount(paymentDiscountValue);
		}

		//
		// Save & validate
		InterfaceWrapperHelper.save(schemaBreak);
		if (!schemaBreak.isValid())
		{
			throw new AdempiereException(schemaBreak.getNotValidReason());
		}
		return toPricingConditionsBreak(schemaBreak);
	}

	@VisibleForTesting
	static void updateSchemaBreakRecordFromRecordFromMatchCriteria(final I_M_DiscountSchemaBreak schemaBreak, final PricingConditionsBreakMatchCriteria matchCriteria)
	{
		if (matchCriteria == null)
		{
			return;
		}

		schemaBreak.setBreakValue(matchCriteria.getBreakValue());
		schemaBreak.setM_Product_ID(ProductId.toRepoId(matchCriteria.getProductId()));
		schemaBreak.setM_Product_Category_ID(ProductCategoryId.toRepoId(matchCriteria.getProductCategoryId()));
		schemaBreak.setManufacturer_ID(BPartnerId.toRepoIdOr(matchCriteria.getProductManufacturerId(), -1));
		schemaBreak.setM_AttributeValue_ID(AttributeValueId.toRepoId(matchCriteria.getAttributeValueId()));
	}

	private void updateSchemaBreakRecordFromSourceScheamaBreakRecord(final I_M_DiscountSchemaBreak schemaBreak, final PricingConditionsBreakId sourcePricingConditionsBreakId)
	{
		if (sourcePricingConditionsBreakId == null)
		{
			return;
		}

		final I_M_DiscountSchemaBreak fromSchemaBreak = load(sourcePricingConditionsBreakId.getDiscountSchemaBreakId(), I_M_DiscountSchemaBreak.class);
		copy().setFrom(fromSchemaBreak)
				.setTo(schemaBreak)
				.addTargetColumnNameToSkip(I_M_DiscountSchemaBreak.COLUMNNAME_AD_Org_ID)
				.addTargetColumnNameToSkip(I_M_DiscountSchemaBreak.COLUMNNAME_M_DiscountSchema_ID)
				.copy();
	}

	private void updateSchemaBreakRecordFromPrice(final I_M_DiscountSchemaBreak schemaBreak, final PriceSpecification price)
	{
		if (price == null)
		{
			// don't change the prices
			return;
		}

		final PriceSpecificationType priceType = price.getType();
		if (priceType == PriceSpecificationType.NONE)
		{
			schemaBreak.setPriceBase(null);
			schemaBreak.setBase_PricingSystem_ID(-1);
			schemaBreak.setPricingSystemSurchargeAmt(BigDecimal.ZERO);

			schemaBreak.setPriceStdFixed(null);
			schemaBreak.setC_Currency(null);
		}
		else if (priceType == PriceSpecificationType.BASE_PRICING_SYSTEM)
		{
			schemaBreak.setPriceBase(X_M_DiscountSchemaBreak.PRICEBASE_PricingSystem);

			schemaBreak.setBase_PricingSystem_ID(price.getBasePricingSystemId().getRepoId());

			final Money surcharge = price.getPricingSystemSurcharge();
			schemaBreak.setPricingSystemSurchargeAmt(surcharge != null ? surcharge.toBigDecimal() : null);
			schemaBreak.setC_Currency_ID(surcharge != null ? surcharge.getCurrencyId().getRepoId() : -1);

			schemaBreak.setPriceStdFixed(null);
		}
		else if (priceType == PriceSpecificationType.FIXED_PRICE)
		{
			schemaBreak.setPriceBase(X_M_DiscountSchemaBreak.PRICEBASE_Fixed);
			schemaBreak.setBase_PricingSystem_ID(-1);
			schemaBreak.setPricingSystemSurchargeAmt(BigDecimal.ZERO);

			final Money fixedPrice = price.getFixedPrice();
			schemaBreak.setPriceStdFixed(fixedPrice != null ? fixedPrice.toBigDecimal() : null);
			schemaBreak.setC_Currency_ID(fixedPrice != null ? fixedPrice.getCurrencyId().getRepoId() : -1);
		}
		else
		{
			throw new AdempiereException("Unknown price override: " + priceType);
		}
	}

	private int retrieveNextSeqNo(final int discountSchemaId)
	{
		final int lastSeqNo = Services.get(IQueryBL.class)
				.createQueryBuilder(I_M_DiscountSchemaBreak.class)
				.addEqualsFilter(I_M_DiscountSchemaBreak.COLUMN_M_DiscountSchema_ID, discountSchemaId)
				.create()
				.maxInt(I_M_DiscountSchemaBreak.COLUMNNAME_SeqNo);

		final int nextSeqNo = lastSeqNo / 10 * 10 + 10;
		return nextSeqNo;
	}

	@Override
	public void copyDiscountSchemaBreaks(
			@NonNull final IQueryFilter<I_M_DiscountSchemaBreak> sourceFilter,
			@NonNull final PricingConditionsId toPricingConditionsId)
	{
		copyDiscountSchemaBreaksWithProductId(sourceFilter, toPricingConditionsId, null/* productId */, false/* allowCopyToSameSchema */);
	}

	@Override
	public void copyDiscountSchemaBreaksWithProductId(
			@NonNull final IQueryFilter<I_M_DiscountSchemaBreak> sourceFilter,
			@NonNull final PricingConditionsId toPricingConditionsId,
			@Nullable final ProductId toProductId,
			final boolean allowCopyToSameSchema)
	{
		final IQueryBL queryBL = Services.get(IQueryBL.class);
		final ICompositeQueryFilter<I_M_DiscountSchemaBreak> breaksFromOtherPricingConditions = queryBL.createCompositeQueryFilter(I_M_DiscountSchemaBreak.class)
				.setJoinAnd()
				.addFilter(sourceFilter);

		if (!allowCopyToSameSchema)
		{
			breaksFromOtherPricingConditions
					.addNotEqualsFilter(I_M_DiscountSchemaBreak.COLUMNNAME_M_DiscountSchema_ID, toPricingConditionsId.getDiscountSchemaId());
		}

		final List<I_M_DiscountSchemaBreak> discountSchemaBreakRecords = retrieveDiscountSchemaBreakRecords(breaksFromOtherPricingConditions);

		for (final I_M_DiscountSchemaBreak schemaBreak : discountSchemaBreakRecords)
		{
			copyDiscountSchemaBreakWithProductId(schemaBreak, toPricingConditionsId, toProductId);
		}
	}

	private List<I_M_DiscountSchemaBreak> retrieveDiscountSchemaBreakRecords(@NonNull final IQueryFilter<I_M_DiscountSchemaBreak> queryFilter)
	{
		return Services.get(IQueryBL.class)
				.createQueryBuilder(I_M_DiscountSchemaBreak.class)
				.filter(queryFilter)
				.create()
				.list();
	}

	private void copyDiscountSchemaBreakWithProductId(
			@NonNull final I_M_DiscountSchemaBreak from,
			@NonNull final PricingConditionsId toPricingConditionsId,
			@Nullable final ProductId toProductId)
	{
		final I_M_DiscountSchemaBreak newBreak = copy()
				.setSkipCalculatedColumns(true)
				.setFrom(from)
				.copyToNew(I_M_DiscountSchemaBreak.class);

		if (toProductId != null)
		{
			newBreak.setM_Product_ID(toProductId.getRepoId());
		}
		newBreak.setSeqNo(retrieveNextSeqNo(toPricingConditionsId.getDiscountSchemaId()));
		newBreak.setM_DiscountSchema_ID(toPricingConditionsId.getDiscountSchemaId());

		saveRecord(newBreak);
	}

	@Override
	public boolean isSingleProductId(final IQueryFilter<I_M_DiscountSchemaBreak> selectionFilter)
	{
		final Set<ProductId> distinctProductIds = retrieveDistinctProductIdsForSelection(selectionFilter);
		return distinctProductIds.size() == 1;
	}

	@Override
	public ProductId retrieveUniqueProductIdForSelectionOrNull(final IQueryFilter<I_M_DiscountSchemaBreak> selectionFilter)
	{
		final Set<ProductId> distinctProductsForSelection = retrieveDistinctProductIdsForSelection(selectionFilter);

		if (distinctProductsForSelection.isEmpty())
		{
			return null;
		}

		if (distinctProductsForSelection.size() > 1)
		{
			throw new AdempiereException("Multiple products or none in the selected rows")
					.appendParametersToMessage()
					.setParameter("selectionFilter", selectionFilter);
		}

		final ProductId uniqueProductId = distinctProductsForSelection.iterator().next();
		return uniqueProductId;
	}

	private Set<ProductId> retrieveDistinctProductIdsForSelection(final IQueryFilter<I_M_DiscountSchemaBreak> selectionFilter)
	{
		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final IQuery<I_M_DiscountSchemaBreak> breaksQuery = queryBL.createQueryBuilder(I_M_DiscountSchemaBreak.class)
				.filter(selectionFilter)
				.create();

		final List<Integer> distinctProductRecordIds = breaksQuery.listDistinct(I_M_DiscountSchemaBreak.COLUMNNAME_M_Product_ID, Integer.class);

		return ProductId.ofRepoIds(distinctProductRecordIds);

	}
}
