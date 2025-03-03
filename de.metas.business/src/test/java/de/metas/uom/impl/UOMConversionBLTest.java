package de.metas.uom.impl;

import static org.assertj.core.api.Assertions.assertThat;

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

import java.math.BigDecimal;

import org.compiere.model.I_C_UOM;
import org.compiere.util.Ini;
import org.junit.Test;

import de.metas.currency.CurrencyPrecision;
import de.metas.money.CurrencyId;
import de.metas.money.Money;
import de.metas.product.ProductId;
import de.metas.product.ProductPrice;
import de.metas.quantity.Quantity;
import de.metas.quantity.QuantityExpectation;
import de.metas.uom.CreateUOMConversionRequest;
import de.metas.uom.IUOMConversionBL;
import de.metas.uom.UOMConstants;
import de.metas.uom.UOMConversionContext;
import de.metas.uom.UomId;
import de.metas.util.Services;

public class UOMConversionBLTest extends UOMTestBase
{
	/** Service under test */
	private UOMConversionBL conversionBL;

	@Override
	protected void afterInit()
	{
		// Service under test
		conversionBL = (UOMConversionBL)Services.get(IUOMConversionBL.class);
	}

	private ProductId createProduct(final String name, final I_C_UOM uom)
	{
		return uomConversionHelper.createProduct(name, uom);
	}

	private ProductId createProduct(final String name, final UomId uomId)
	{
		return uomConversionHelper.createProduct(name, uomId);
	}

	@Test
	public void adjustToUOMPrecisionWithoutRoundingIfPossible()
	{
		final int uomPrecision = 2;

		adjustToUOMPrecisionWithoutRoundingIfPossible("0.0000000000", uomPrecision, "0.00", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("0.0000000000", uomPrecision, "0.00", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-0.0000000000", uomPrecision, "0.00", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.00000000000", uomPrecision, "12.00", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.00000000000", uomPrecision, "-12.00", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("10.0", uomPrecision, "10.00", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-10.0", uomPrecision, "-10.00", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.3", uomPrecision, "12.30", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.3", uomPrecision, "-12.30", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.30", uomPrecision, "12.30", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.30", uomPrecision, "-12.30", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("10.00", uomPrecision, "10.00", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-10.00", uomPrecision, "-10.00", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.34", uomPrecision, "12.34", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.34", uomPrecision, "-12.34", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("10.000", uomPrecision, "10.00", 2);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-10.000", uomPrecision, "-10.00", 2);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.345", uomPrecision, "12.345", 3);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.345", uomPrecision, "-12.345", 3);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.34500", uomPrecision, "12.345", 3);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.34500", uomPrecision, "-12.345", 3);

		adjustToUOMPrecisionWithoutRoundingIfPossible("12.345000000000", uomPrecision, "12.345", 3);
		adjustToUOMPrecisionWithoutRoundingIfPossible("-12.345000000000", uomPrecision, "-12.345", 3);
	}

	private void adjustToUOMPrecisionWithoutRoundingIfPossible(
			final String qtyStr, final int uomPrecision,
			final String qtyStrExpected,
			final int uomPrecisionExpected)
	{
		final I_C_UOM uom = uomConversionHelper.createUOM(uomPrecision);
		final BigDecimal qty = new BigDecimal(qtyStr);

		final BigDecimal qtyRounded = conversionBL.adjustToUOMPrecisionWithoutRoundingIfPossible(qty, uom);
		assertThat(qtyRounded)
				.as("Rounded qty value shall equal with initial qty value")
				.isEqualByComparingTo(qty);

		assertThat(qtyRounded.scale())
				.as("Invalid rounded qty precision for '" + qtyRounded + "'")
				.isEqualTo(uomPrecisionExpected);

		assertThat(qtyRounded).isEqualTo(qtyStrExpected);
	}

	@Test
	public void convertQty()
	{
		// Mocking the case for Folie AB Alicesalat (1000 lm)
		// Multiply Rate = 1500000.000000000000;
		// Divide Rate = 0.000000666667

		final I_C_UOM rolle = uomConversionHelper.createUOM("Rolle", 2, 0, "RL");
		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 2, 0, "mm");

		final ProductId folieId = createProduct("Folie", rolle);

		final BigDecimal multiplyRate = new BigDecimal("1500000.000000000000");
		final BigDecimal divideRate = new BigDecimal("0.000000666667");

		uomConversionHelper.createUOMConversion(
				folieId,
				rolle,
				millimeter,
				multiplyRate,
				divideRate);

		{
			final BigDecimal qtyToConvert = BigDecimal.ONE;
			final BigDecimal convertedQty = conversionBL.convertQty(folieId, qtyToConvert, rolle, millimeter);
			assertThat(convertedQty).isEqualTo(new BigDecimal("1500000.00"));
		}

		{
			final BigDecimal qtyToConvert = new BigDecimal(1500000);
			final BigDecimal convertedQty = conversionBL.convertQty(folieId, qtyToConvert, millimeter, rolle);
			assertThat(convertedQty).isEqualTo(new BigDecimal("1.00"));
		}

	}

	@Test
	public void convertQty_NoProductInConversion()
	{
		final I_C_UOM rolle = uomConversionHelper.createUOM("Rolle", 2, 0, "RL");

		final ProductId folieProductId = createProduct("Folie", rolle);

		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 2, 0, "mm");
		final I_C_UOM meter = uomConversionHelper.createUOM("meter", 2, 0, "MTR");

		uomConversionHelper.createUOMConversion(CreateUOMConversionRequest.builder()
				.fromUomId(toUomId(meter))
				.toUomId(toUomId(millimeter))
				.fromToMultiplier(new BigDecimal("1000"))
				.toFromMultiplier(new BigDecimal("0.001"))
				.build());

		final BigDecimal convertedQty = conversionBL.convertQty(
				folieProductId,
				new BigDecimal(2000),
				millimeter,
				meter);

		assertThat(convertedQty).isEqualTo(new BigDecimal("2.00"));
	}

	@Test
	public void convert_GeneralConversion()
	{
		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 2, 0, "mm");
		final I_C_UOM meter = uomConversionHelper.createUOM("Meter", 2, 0, "MTR");
		final BigDecimal multiplyRate = new BigDecimal(1000);
		final BigDecimal divideRate = new BigDecimal("1.00000000000000000000");

		uomConversionHelper.createUOMConversion(
				(ProductId)null,
				meter,
				millimeter,
				multiplyRate,
				divideRate);

		final BigDecimal qtyToConvert = new BigDecimal(2);
		final BigDecimal convertedQty = conversionBL.convert(meter, millimeter, qtyToConvert, true);

		assertThat(convertedQty).isEqualByComparingTo("2000");
	}

	@Test
	public void convertQty_GeneralConversion()
	{
		final I_C_UOM rolle = uomConversionHelper.createUOM("Rolle", 2, 0, "RL");

		final ProductId folieId = createProduct("Folie", rolle);

		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 2, 0, "mm");

		final BigDecimal multiplyRate = new BigDecimal("1500000.000000000000");
		final BigDecimal divideRate = new BigDecimal("0.000000666667");

		uomConversionHelper.createUOMConversion(
				(ProductId)null,
				rolle,
				millimeter,
				multiplyRate,
				divideRate);

		final BigDecimal qtyToConvert = new BigDecimal(3000000);
		final BigDecimal convertedQty = conversionBL.convertQty(folieId, qtyToConvert, millimeter, rolle);

		assertThat(convertedQty).isEqualTo("2.00");
	}

	@Test
	public void convert_GeneralConversion_UseStdPrecision()
	{
		final I_C_UOM rolle = uomConversionHelper.createUOM("Rolle", 2, 0, "RL");

		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 3, 2, "mm");

		final BigDecimal multiplyRate = new BigDecimal("1500000.1290000000");
		final BigDecimal divideRate = new BigDecimal("0.000000666667");

		uomConversionHelper.createUOMConversion(
				(ProductId)null,
				rolle,
				millimeter,
				multiplyRate,
				divideRate);

		final BigDecimal qtyToConvert = new BigDecimal(2);
		final boolean useStdPrecision = true;
		final BigDecimal convertedQty = conversionBL.convert(rolle, millimeter, qtyToConvert, useStdPrecision);

		assertThat(convertedQty).isEqualTo("3000000.258");
	}

	@Test
	public void convert_GeneralConversion_DoNotUseStdPrecision()
	{
		final I_C_UOM rolle = uomConversionHelper.createUOM("Rolle", 2, 0, "RL");

		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 3, 2, "mm");

		final BigDecimal multiplyRate = new BigDecimal("1500000.1290000000");
		final BigDecimal divideRate = new BigDecimal("0.000000666667");

		uomConversionHelper.createUOMConversion(
				(ProductId)null,
				rolle,
				millimeter,
				multiplyRate,
				divideRate);

		final BigDecimal qtyToConvert = new BigDecimal(2);
		final boolean useStdPrecision = false;
		final BigDecimal convertedQty = conversionBL.convert(rolle, millimeter, qtyToConvert, useStdPrecision);

		assertThat(convertedQty).isEqualTo("3000000.26");
	}

	@Test
	public void getTimeConversionRate()
	{
		final I_C_UOM minute = uomConversionHelper.createUOM(
				"Minute",
				1,
				0,
				UOMConstants.X12_MINUTE);

		final I_C_UOM hour = uomConversionHelper.createUOM(
				"Hour",
				1,
				0,
				UOMConstants.X12_HOUR);

		final I_C_UOM day = uomConversionHelper.createUOM(
				"Day",
				1,
				0,
				UOMConstants.X12_DAY);

		final I_C_UOM week = uomConversionHelper.createUOM(
				"Week",
				1,
				0,
				UOMConstants.X12_WEEK);

		final I_C_UOM month = uomConversionHelper.createUOM(
				"Month",
				1,
				0,
				UOMConstants.X12_MONTH);

		final I_C_UOM year = uomConversionHelper.createUOM(
				"Year",
				1,
				0,
				UOMConstants.X12_YEAR);

		{
			final BigDecimal minutesPerDay = new BigDecimal(60 * 24);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(day, minute);
			assertThat(rate).isEqualTo(minutesPerDay);
		}

		final BigDecimal daysPerWeek = new BigDecimal(7);
		{
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(week, day);
			assertThat(rate).isEqualTo(daysPerWeek);
		}

		final BigDecimal hoursPerDay = new BigDecimal(24);
		{
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(day, hour);
			assertThat(rate).isEqualTo(hoursPerDay);
		}

		{
			final BigDecimal hoursPerWeek = daysPerWeek.multiply(hoursPerDay);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(week, hour);
			assertThat(rate).isEqualTo(hoursPerWeek);
		}

		{
			final BigDecimal weeksPerMonth = new BigDecimal(4);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(month, week);
			assertThat(rate).isEqualTo(weeksPerMonth);
		}

		{
			final BigDecimal daysPerMinute = new BigDecimal(1.0 / 1440.0);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(minute, day);
			assertThat(rate).isEqualTo(daysPerMinute);
		}

		{
			final BigDecimal weeksPerDay = new BigDecimal(1.0 / 7.0);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(day, week);
			assertThat(rate).isEqualTo(weeksPerDay);
		}

		{
			final BigDecimal daysPerHour = new BigDecimal(1.0 / 24.0);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(hour, day);
			assertThat(rate).isEqualTo(daysPerHour);
		}

		{
			final BigDecimal weeksPerHour = new BigDecimal(1.0 / 168.0);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(hour, week);
			assertThat(rate).isEqualTo(weeksPerHour);
		}

		{
			final BigDecimal monthsPerWeek = new BigDecimal(1.0 / 4.0);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(week, month);
			assertThat(rate).isEqualTo(monthsPerWeek);
		}

		{
			final BigDecimal minutesPerYear = new BigDecimal(1.0 / 525600.0);
			final BigDecimal rate = conversionBL.getTimeConversionRateAsBigDecimal(minute, year);
			assertThat(rate).isEqualTo(minutesPerYear);
		}
	}

	/**
	 * Convert two hours into 120 minutes.
	 */
	@Test
	public void convertFromProductUOM_Minutes_To_Hour()
	{
		final I_C_UOM minute = uomConversionHelper.createUOM(
				"Minute",
				1,
				0,
				UOMConstants.X12_MINUTE);

		final I_C_UOM hour = uomConversionHelper.createUOM(
				"Hour",
				1,
				0,
				UOMConstants.X12_HOUR);

		uomConversionHelper.createUOMConversion(
				(ProductId)null,
				minute,
				hour,
				new BigDecimal("0.016666666667"),// multiply rate
				new BigDecimal("60") // divide rate
		);

		final ProductId hourProductId = createProduct("HourProduct", hour);
		final BigDecimal result = conversionBL.convertFromProductUOM(hourProductId, minute, new BigDecimal("2"));
		assertThat(result).isEqualByComparingTo("120");
	}

	/**
	 * @task http://dewiki908/mediawiki/index.php/07433_Folie_Zuteilung_Produktion_Fertigstellung_POS_%28102170996938%29
	 */
	@Test
	public void convertToProductUOM_CheckProductUOMPrecisionIsUsed()
	{
		final I_C_UOM uomMillimeter = uomConversionHelper.createUOM("Millimeter", 2, 4);
		final I_C_UOM uomRolle = uomConversionHelper.createUOM("Rolle", 4, 4);

		final ProductId productId = createProduct("Folie", uomRolle);

		//
		// Conversion: Rolle -> Millimeter
		// 1 Rolle = 1_500_000 millimeters
		uomConversionHelper.createUOMConversion(
				productId,
				uomRolle,
				uomMillimeter,
				new BigDecimal(1_500_000), // multiply rate
				new BigDecimal(0.000000666667)  // divide rate
		);

		// Expected converted qty: 0.0191 = 28600 x 0.000000666667(divideRate) rounded to 4 digits
		// NOTE: we particulary picked those numbers to make sure that Product UOM's precision (i.e. Rolle, precision=4) is used and not source UOM's precision
		final BigDecimal qtyConvertedActual = conversionBL.convertToProductUOM(productId, uomMillimeter, new BigDecimal("28600"));

		// NOTE: we don't use compareTo because we also want to match the precision
		assertThat(qtyConvertedActual).isEqualTo("0.0191");
	}

	@Test
	public void convertFromProductUOM_CheckProductUOMPrecisionIsUsed()
	{
		final I_C_UOM uomMillimeter = uomConversionHelper.createUOM("Millimeter", 2, 4);
		final I_C_UOM uomRolle = uomConversionHelper.createUOM("Rolle", 4, 4);

		final ProductId productId = createProduct("Folie", uomMillimeter);

		//
		// Conversion: Rolle -> Millimeter
		// 1 Rolle = 1_500_000 millimeters
		uomConversionHelper.createUOMConversion(
				productId,
				uomRolle,
				uomMillimeter,
				new BigDecimal(1_500_000), // multiply rate
				new BigDecimal(0.000000666667)  // divide rate
		);

		// Expected converted qty: 0.0191 = 28600 x 0.000000666667(divideRate) rounded to 4 digits
		// NOTE: we particularly picked those numbers to make sure that Product UOM's precision (i.e. Rolle, precision=4) is used and not source UOM's precision
		final BigDecimal qtyConvertedActual = conversionBL.convertFromProductUOM(productId, uomRolle, new BigDecimal("28600"));

		// NOTE: we don't use compareTo because we also want to match the precision
		assertThat(qtyConvertedActual).isEqualTo("0.0191");
	}

	@Test
	public void testServerSide_NoProductConversion()
	{
		Ini.setClient(false);
		final I_C_UOM rolle = uomConversionHelper.createUOM("Rolle", 2, 0, "RL");

		final ProductId folieId = createProduct("Folie", rolle);

		final I_C_UOM millimeter = uomConversionHelper.createUOM("Millimeter", 2, 0, "mm");

		final BigDecimal multiplyRate = new BigDecimal("1500000.000000000000");
		final BigDecimal divideRate = new BigDecimal("0.000000666667");

		uomConversionHelper.createUOMConversion(
				(ProductId)null,
				rolle,
				millimeter,
				multiplyRate,
				divideRate);

		final BigDecimal qtyToConvert = new BigDecimal(3000000);
		final BigDecimal convertedQty = Services.get(IUOMConversionBL.class).convertQty(folieId, qtyToConvert, millimeter, rolle);

		assertThat(convertedQty).isEqualByComparingTo("2");
	}

	@Test
	public void convertFromProductUOM_DirectConversionShallBeUsed()
	{
		final I_C_UOM uom1 = uomConversionHelper.createUOM("uom1", 2, 4);
		final I_C_UOM uom2 = uomConversionHelper.createUOM("uom2", 2, 4);
		final ProductId productId = createProduct("product", uom1);

		uomConversionHelper.createUOMConversion(productId, uom1, uom2, new BigDecimal("2"), new BigDecimal("3"));

		final BigDecimal rate = conversionBL.convertFromProductUOM(productId, uom2, BigDecimal.ONE);
		assertThat(rate)
				.as("conversion rate for  uom1->uom2")
				.isEqualTo("2.00");
	}

	@Test
	public void getRateForConversionFromProductUOM_ReverseConversionShallBeUsed()
	{
		final I_C_UOM uom1 = uomConversionHelper.createUOM("uom1", 2, 4);
		final I_C_UOM uom2 = uomConversionHelper.createUOM("uom2", 2, 4);
		final ProductId productId = createProduct("product", uom1);

		uomConversionHelper.createUOMConversion(productId, uom2, uom1, new BigDecimal("2"), new BigDecimal("3"));

		final BigDecimal rate = conversionBL.convertFromProductUOM(productId, uom2, BigDecimal.ONE);
		assertThat(rate)
				.as("conversion rate for  uom1->uom2")
				.isEqualTo("3.00");
	}

	@Test
	public void convertToProductUOM_DirectConversionShallBeUsed()
	{
		final I_C_UOM uom1 = uomConversionHelper.createUOM("uom1", 2, 4);
		final I_C_UOM uom2 = uomConversionHelper.createUOM("uom2", 2, 4);
		final ProductId productId = createProduct("product", uom1);

		uomConversionHelper.createUOMConversion(productId, uom1, uom2, new BigDecimal("2"), new BigDecimal("3"));

		final BigDecimal rate = conversionBL.convertToProductUOM(productId, BigDecimal.ONE, toUomId(uom2));
		assertThat(rate)
				.as("conversion rate for  uom1->uom2")
				.isEqualTo("3.00");
	}

	@Test
	public void convertToProductUOM_ReverseConversionShallBeUsed()
	{
		final I_C_UOM uom1 = uomConversionHelper.createUOM("uom1", 2, 4);
		final I_C_UOM uom2 = uomConversionHelper.createUOM("uom2", 2, 4);
		final ProductId productId = createProduct("product", uom1);

		uomConversionHelper.createUOMConversion(productId, uom2, uom1, new BigDecimal("2"), new BigDecimal("3"));

		final BigDecimal rate = conversionBL.convertToProductUOM(productId, BigDecimal.ONE, toUomId(uom2));

		assertThat(rate)
				.as("conversion rate for  uom1->uom2")
				.isEqualTo("2.00");
	}

	@Test
	public void convertTo_CurrentUOM()
	{
		final BigDecimal qty = new BigDecimal("1234");
		final I_C_UOM uom = uomConversionHelper.createUOM("UOM1", 2);
		final BigDecimal sourceQty = new BigDecimal("1235");
		final I_C_UOM sourceUOM = uomConversionHelper.createUOM("UOM2", 2);
		final Quantity quantity = new Quantity(qty, uom, sourceQty, sourceUOM);

		final UOMConversionContext conversionCtx = null; // don't care, shall not be used
		final Quantity quantityConv = conversionBL.convertQuantityTo(quantity, conversionCtx, uom);
		assertThat(quantityConv).isSameAs(quantity);
	}

	@Test
	public void convertTo_SourceUOM()
	{
		final BigDecimal qty = new BigDecimal("1234");
		final I_C_UOM uom = uomConversionHelper.createUOM("UOM1", 2);
		final BigDecimal sourceQty = new BigDecimal("1235");
		final I_C_UOM sourceUOM = uomConversionHelper.createUOM("UOM2", 2);
		final Quantity quantity = new Quantity(qty, uom, sourceQty, sourceUOM);

		final UOMConversionContext conversionCtx = null; // don't care, shall not be used
		final Quantity quantityConv = conversionBL.convertQuantityTo(quantity, conversionCtx, sourceUOM);
		new QuantityExpectation()
				.sameQty(sourceQty)
				.uom(sourceUOM)
				.sameSourceQty(qty)
				.sourceUOM(uom)
				.assertExpected("converted quantity", quantityConv);
	}

	@Test
	public void convertTo_OtherUOM()
	{
		//
		// Create Quantity
		final BigDecimal qty = new BigDecimal("1234");
		final I_C_UOM uom = uomConversionHelper.createUOM("UOM", 2);
		final BigDecimal sourceQty = new BigDecimal("1235");
		final I_C_UOM sourceUOM = uomConversionHelper.createUOM("UOM_Source", 2);
		final Quantity quantity = new Quantity(qty, uom, sourceQty, sourceUOM);

		//
		// Create the other UOM
		final I_C_UOM otherUOM = uomConversionHelper.createUOM("UOM_Other", 2);

		//
		// Create conversion rate: uom -> otherUOM (for product)
		final ProductId productId = createProduct("product", uom);
		uomConversionHelper.createUOMConversion(productId, uom, otherUOM, new BigDecimal("2"), new BigDecimal("0.5"));

		//
		// Create UOM Conversion context
		final UOMConversionContext uomConversionCtx = UOMConversionContext.of(productId);

		//
		// Convert the quantity to "otherUOM" and validate
		final Quantity quantityConv = conversionBL.convertQuantityTo(quantity, uomConversionCtx, otherUOM);
		new QuantityExpectation()
				.qty("2468")
				.uom(otherUOM)
				.sourceQty(qty)
				.sourceUOM(uom)
				.assertExpected("converted quantity", quantityConv);
	}

	@Test
	public void convertProductPriceToUom_1()
	{
		final CurrencyId currencyId = CurrencyId.ofRepoId(1);
		final CurrencyPrecision currencyPrecision = CurrencyPrecision.ofInt(3);

		final UomId uomTonnes = toUomId(uomConversionHelper.createUOM("Metric Tonnes", 3, 0));
		final UomId uomTripOfSand = toUomId(uomConversionHelper.createUOM("Trip of Sand", 2, 0));

		final ProductId productId = createProduct("Sand", uomTonnes);

		//
		// Conversion: 1 Trip = 27 Tonnes
		uomConversionHelper.createUOMConversion(CreateUOMConversionRequest.builder()
				.productId(productId)
				.fromUomId(uomTonnes)
				.toUomId(uomTripOfSand)
				.fromToMultiplier(new BigDecimal("0.037037037037"))
				.toFromMultiplier(new BigDecimal("27"))
				.build());

		final ProductPrice pricePerTrip = ProductPrice.builder()
				.productId(productId)
				.uomId(uomTripOfSand)
				.money(Money.of(950, currencyId))
				.build();
		System.out.println("Price/Trip: " + pricePerTrip);

		//
		// Convert Price/Trip to Price/Tonne
		final ProductPrice pricePerTonne = conversionBL.convertProductPriceToUom(pricePerTrip, uomTonnes, currencyPrecision);
		System.out.println("Price/Tonne: " + pricePerTonne);
		assertThat(pricePerTonne.toBigDecimal()).isEqualTo("35.185");
		assertThat(pricePerTonne.getUomId()).isEqualTo(uomTonnes);
	}

	@Test
	public void convertProductPriceToUom_2()
	{
		final CurrencyId currencyId = CurrencyId.ofRepoId(1);
		final CurrencyPrecision currencyPrecision = CurrencyPrecision.ofInt(3);

		final UomId uomSheet = toUomId(uomConversionHelper.createUOM("Sheet", 3, 3));
		final UomId uomSQM = toUomId(uomConversionHelper.createUOM("Square meter", 3, 3));

		final ProductId productId = createProduct("Clear glass", uomSheet);

		//
		// Conversion: 1 Sheet = 7.2225 SQMs
		uomConversionHelper.createUOMConversion(CreateUOMConversionRequest.builder()
				.productId(productId)
				.fromUomId(uomSheet)
				.toUomId(uomSQM)
				.fromToMultiplier(new BigDecimal("7.2225"))
				.toFromMultiplier(new BigDecimal("0.1384562132225684"))
				.build());

		final ProductPrice pricePerSQM = ProductPrice.builder()
				.productId(productId)
				.uomId(uomSQM)
				.money(Money.of(new BigDecimal("42.03"), currencyId))
				.build();
		System.out.println("Price/SQM: " + pricePerSQM);

		//
		// Convert Price/SQM to Price/Tone
		final ProductPrice pricePerSheet = conversionBL.convertProductPriceToUom(pricePerSQM, uomSheet, currencyPrecision);
		System.out.println("Price/Sheet: " + pricePerSheet);
		assertThat(pricePerSheet.toBigDecimal()).isEqualTo("303.562");
		assertThat(pricePerSheet.getUomId()).isEqualTo(uomSheet);
	}

	private static final UomId toUomId(final I_C_UOM uom)
	{
		return UomId.ofRepoId(uom.getC_UOM_ID());
	}
}
