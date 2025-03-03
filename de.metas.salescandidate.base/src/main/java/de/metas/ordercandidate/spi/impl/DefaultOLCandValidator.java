package de.metas.ordercandidate.spi.impl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Properties;

import org.adempiere.ad.persistence.ModelDynAttributeAccessor;
import org.adempiere.ad.service.IDeveloperModeBL;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.compiere.model.I_C_BPartner_Location;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import de.metas.i18n.IMsgBL;
import de.metas.logging.LogManager;
import de.metas.ordercandidate.api.IOLCandBL;
import de.metas.ordercandidate.api.IOLCandEffectiveValuesBL;
import de.metas.ordercandidate.model.I_C_OLCand;
import de.metas.ordercandidate.spi.IOLCandValidator;
import de.metas.pricing.IPricingResult;
import de.metas.pricing.PricingSystemId;
import de.metas.product.ProductId;
import de.metas.tax.api.TaxCategoryId;
import de.metas.uom.IUOMConversionBL;
import de.metas.uom.IUOMDAO;
import de.metas.uom.UomId;
import de.metas.util.Services;
import lombok.NonNull;

/*
 * #%L
 * de.metas.salescandidate.base
 * %%
 * Copyright (C) 2019 metas GmbH
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
 * 
 * @task http://dewiki908/mediawiki/index.php/09623_old_incoice_location_taken_sometimes_in_excel_import_%28104714160405%29
 */
@Component
public class DefaultOLCandValidator implements IOLCandValidator
{
	// services
	private static final Logger logger = LogManager.getLogger(DefaultOLCandValidator.class);
	private final IMsgBL msgBL = Services.get(IMsgBL.class);
	private final IOLCandBL olCandBL = Services.get(IOLCandBL.class);
	private final IOLCandEffectiveValuesBL olCandEffectiveValuesBL = Services.get(IOLCandEffectiveValuesBL.class);
	private final IUOMDAO uomsRepo = Services.get(IUOMDAO.class);
	private final IUOMConversionBL uomConversionBL = Services.get(IUOMConversionBL.class);
	private final IDeveloperModeBL developerModeBL = Services.get(IDeveloperModeBL.class);

	// error messages
	private static final String ERR_Bill_Location_Inactive = "ERR_Bill_Location_Inactive";
	private static final String ERR_C_BPartner_Location_Effective_Inactive = "ERR_C_BPartner_Location_Effective_Inactive";
	private static final String ERR_DropShip_Location_Inactive = "ERR_DropShip_Location_Inactive";
	private static final String ERR_HandOver_Location_Inactive = "ERR_HandOver_Location_Inactive";

	/**
	 * Dynamic attribute name used to pass on the pricing result obtained by this class to potential listeners like {@link OLCandPricingASIListener}.
	 *
	 * @task http://dewiki908/mediawiki/index.php/08803_ADR_from_Partner_versus_Pricelist
	 */
	private static final ModelDynAttributeAccessor<I_C_OLCand, IPricingResult> DYNATTR_OLCAND_PRICEVALIDATOR_PRICING_RESULT = new ModelDynAttributeAccessor<>(DefaultOLCandValidator.class.getSimpleName() + "#pricingResult", IPricingResult.class);

	@Override
	public boolean validate(final I_C_OLCand olCand)
	{
		return validateLocation(olCand)
				&& validatePrice(olCand)
				&& validateUOM(olCand);
	}

	private boolean validateLocation(final I_C_OLCand olCand)
	{
		// Error messages about which of the locations are not active
		final StringBuilder msg = new StringBuilder();

		// flag to tell if the OLCand locations are valid. In case one of them is not, the flag will be false.
		boolean isValid = true;

		final Properties ctx = InterfaceWrapperHelper.getCtx(olCand);

		// Bill Location
		final I_C_BPartner_Location billLocation = olCandEffectiveValuesBL.getBill_Location_Effective(olCand);

		if (billLocation != null && !billLocation.isActive())
		{
			final String localMsg = msgBL.getMsg(ctx, ERR_Bill_Location_Inactive);
			msg.append(localMsg + "\n");
			isValid = false;
		}

		// C_BPartner_Location_Effective

		final I_C_BPartner_Location bpLocationEffective = olCandEffectiveValuesBL.getC_BP_Location_Effective(olCand);
		if (bpLocationEffective != null && !bpLocationEffective.isActive())
		{
			final String localMsg = msgBL.getMsg(ctx, ERR_C_BPartner_Location_Effective_Inactive);
			msg.append(localMsg + "\n");
			isValid = false;
		}

		// DropShip_Location
		final I_C_BPartner_Location dropShipLocation = olCandEffectiveValuesBL.getDropShip_Location_Effective(olCand);

		if (dropShipLocation != null && !dropShipLocation.isActive())
		{
			final String localMsg = msgBL.getMsg(ctx, ERR_DropShip_Location_Inactive);
			msg.append(localMsg + "\n");
			isValid = false;
		}

		// HandOver_Location
		final I_C_BPartner_Location handOverLocation = olCandEffectiveValuesBL.getHandOver_Location_Effective(olCand);
		if (handOverLocation != null && !handOverLocation.isActive())
		{
			final String localMsg = msgBL.getMsg(ctx, ERR_HandOver_Location_Inactive);
			msg.append(localMsg + "\n");
			isValid = false;
		}

		if (!isValid)
		{
			olCand.setIsError(true);
			olCand.setErrorMsg(msgBL.parseTranslation(ctx, msg.toString()));
			return false;
		}

		return true;
	}

	private boolean validatePrice(final I_C_OLCand olCand)
	{
		olCand.setErrorMsg(null);
		olCand.setIsError(false);

		if (olCand.isManualPrice())
		{
			// Set the price actual as the price entered
			olCand.setPriceActual(olCand.getPriceEntered());

			final Properties ctx = InterfaceWrapperHelper.getCtx(olCand);

			// still, make sure that we have a currency set
			if (olCand.getC_Currency_ID() <= 0)
			{
				olCand.setIsError(true);
				final String msg = "@NotFound@ @C_Currency@";

				olCand.setErrorMsg(msgBL.parseTranslation(ctx, msg));
				return false;
			}

			final IPricingResult pricingResult = getPricingResult(olCand);
			if (pricingResult == null || pricingResult.getPricingSystemId() == null || pricingResult.getPricingSystemId().isNone())
			{
				olCand.setIsError(true);
				final String msg = "@NotFound@ @M_PricingSystem_ID@";
				olCand.setErrorMsg(msgBL.parseTranslation(ctx, msg));
				return false;
			}
			olCand.setM_PricingSystem_ID(pricingResult.getPricingSystemId().getRepoId());

			if (pricingResult == null || pricingResult.getTaxCategoryId() == null)
			{
				olCand.setIsError(true);
				final String msg = "@NotFound@ @C_TaxCategory_ID@";
				olCand.setErrorMsg(msgBL.parseTranslation(ctx, msg));
				return false;
			}
			else
			{
				olCand.setC_TaxCategory_ID(pricingResult.getTaxCategoryId().getRepoId());

				// set the internal pricing info for the user's information, if we have it
				olCand.setPriceInternal(pricingResult.getPriceStd());
				olCand.setPrice_UOM_Internal_ID(UomId.toRepoId(pricingResult.getPriceUomId()));

				// further validation on manual price is not needed
				return true;
			}
		}

		// task 08072
		// before validating, unset the isserror and set the error message on null.
		// this way they will be up to date after validation
		olCand.setIsError(false);
		olCand.setErrorMsg(null);

		// task 08803: reset the ASI because we do *not* want a pre-existing ASI (from a pre-existing validation) to influence the price.
		// As it is now, the price (incl. pricing ASI) shall be determined only by the product, packaging, and the pricing master data, but not by the OLcand's current ASI values.
		// NOTE: this was introduced mainly for EDI C_OLCand imports.
		olCand.setM_AttributeSetInstance(null);

		final IPricingResult pricingResult = getPricingResult(olCand);
		if (pricingResult == null)
		{
			return false;
		}

		final BigDecimal priceInternal = pricingResult.getPriceStd();
		final UomId priceUOMInternalId = pricingResult.getPriceUomId();

		// FIXME: move this part to handlingUnits !!!
		if (priceUOMInternalId != null
				&& "TU".equals(uomsRepo.getX12DE355ById(priceUOMInternalId)))
		{
			// this olCand has a TU/Gebinde price-UOMthat mean that despite the imported UOM may be PCE, we import UOM="TU" into our order line.
			olCand.setC_UOM_Internal_ID(priceUOMInternalId.getRepoId());
		}
		else
		{
			// this olCand has no TU/Gebinde price-UOM, so we just continuer with the olCand's imported UOM
			olCand.setC_UOM_Internal_ID(olCand.getC_UOM_ID());
		}

		// note: the customer's price remains as it is in the "PriceEntered" column

		// set the internal pricing info for the user's information
		olCand.setPriceInternal(priceInternal);
		olCand.setPrice_UOM_Internal_ID(UomId.toRepoId(priceUOMInternalId));

		olCand.setPriceActual(priceInternal);
		olCand.setC_Currency_ID(pricingResult.getCurrencyRepoId());

		olCand.setC_TaxCategory_ID(TaxCategoryId.toRepoId(pricingResult.getTaxCategoryId()));

		olCand.setM_PricingSystem_ID(PricingSystemId.toRepoId(pricingResult.getPricingSystemId()));

		// task 08803: we provide the pricing result and expect that OLCandPricingASIListener will keep the ASI up to date
		DYNATTR_OLCAND_PRICEVALIDATOR_PRICING_RESULT.setValue(olCand, pricingResult);

		return true;
	}

	private IPricingResult getPricingResult(@NonNull final I_C_OLCand olCand)
	{
		try
		{
			final BigDecimal qtyOverride = null;
			final LocalDate datePromisedEffective = TimeUtil.asLocalDate(olCandEffectiveValuesBL.getDatePromised_Effective(olCand));
			final IPricingResult pricingResult = olCandBL.computePriceActual(olCand, qtyOverride, PricingSystemId.NULL, datePromisedEffective);

			return pricingResult;
		}
		catch (final AdempiereException e)
		{
			olCand.setErrorMsg(e.getLocalizedMessage());
			olCand.setIsError(true);

			// Warn developer that something went wrong.
			// In this way he/she can early see the issue and where it happened.
			if (developerModeBL.isEnabled())
			{
				logger.warn(e.getLocalizedMessage(), e);
			}

		}
		return null;
	}

	public static IPricingResult getPreviouslyCalculatedPricingResultOrNull(final I_C_OLCand olCand)
	{
		return DYNATTR_OLCAND_PRICEVALIDATOR_PRICING_RESULT.getValue(olCand);
	}

	/**
	 * Validates the UOM conversion; we will need convertToProductUOM in order to get the QtyOrdered in the order line.
	 */
	private boolean validateUOM(@NonNull final I_C_OLCand olCand)
	{
		try
		{
			final ProductId productId = olCandEffectiveValuesBL.getM_Product_Effective_ID(olCand);
			uomConversionBL.convertToProductUOM(
					productId,
					olCandEffectiveValuesBL.getC_UOM_Effective(olCand),
					olCand.getQty());
		}
		catch (AdempiereException e)
		{
			olCand.setErrorMsg(e.getLocalizedMessage());
			olCand.setIsError(true);
			return false;
		}
		return true;
	}

}
