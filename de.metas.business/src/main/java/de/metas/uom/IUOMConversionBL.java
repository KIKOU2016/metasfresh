package de.metas.uom;

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
import java.util.Collection;
import java.util.Optional;

import org.compiere.model.I_C_UOM;

import de.metas.currency.CurrencyPrecision;
import de.metas.product.ProductId;
import de.metas.product.ProductPrice;
import de.metas.quantity.Quantity;
import de.metas.util.ISingletonService;
import lombok.NonNull;

public interface IUOMConversionBL extends ISingletonService
{
	/**
	 * Convert quantity from <code>uomFrom</code> to <code>uomTo</code>
	 *
	 * @return converted quantity; never return NULL.
	 */
	BigDecimal convertQty(
			final ProductId productId,
			final BigDecimal qty,
			@NonNull final I_C_UOM uomFrom,
			@NonNull final I_C_UOM uomTo);

	/**
	 * Convert quantity from <code>uomFrom</code> to <code>uomTo</code>
	 *
	 * @return converted quantity; never return NULL.
	 */
	BigDecimal convertQty(UOMConversionContext conversionCtx, BigDecimal qty, I_C_UOM uomFrom, I_C_UOM uomTo);

	BigDecimal convertQty(UOMConversionContext conversionCtx, BigDecimal qty, UomId uomFrom, UomId uomTo);

	/**
	 * Creates a new {@link Quantity} object by converting the given {@code quantity} to the given {@code uomTo}.
	 *
	 * The new {@link Quantity} object will have {@link #getQty()} and {@link #getUOM()} as their source Qty/UOM.
	 *
	 * @return new Quantity converted to given <code>uom</code>.
	 */
	Quantity convertQuantityTo(Quantity quantity, UOMConversionContext conversionCtx, I_C_UOM uomTo);

	Quantity convertQuantityTo(Quantity quantity, UOMConversionContext conversionCtx, UomId uomToId);

	/**
	 * Convert quantity from <code>uomFrom</code> to product's stocking UOM.
	 *
	 * @param conversionCtx
	 * @param qty
	 * @param uomFrom
	 * @return converted quantity; never return NULL.
	 */
	BigDecimal convertQtyToProductUOM(UOMConversionContext conversionCtx, BigDecimal qty, I_C_UOM uomFrom);

	/**
	 * Convert price from <code>uomFrom</code> to <code>uomTo</code>
	 *
	 * @param product
	 * @param price
	 * @param uomFrom may not be <code>null</code>.
	 * @param uomTo may not be <code>null</code>.
	 * @param pricePrecision precision to be used for resulting price
	 * @return converted price using <code>pricePrecision</code>; never return NULL.
	 */
	BigDecimal convertPrice(int productId, BigDecimal price, I_C_UOM uomFrom, I_C_UOM uomTo, int pricePrecision);

	/**
	 * Rounds given qty to UOM standard precision.
	 *
	 * If qty's actual precision is bigger than UOM standard precision then the qty WON'T be rounded.
	 *
	 * @param qty
	 * @param uom
	 * @return qty rounded to UOM precision
	 */
	BigDecimal adjustToUOMPrecisionWithoutRoundingIfPossible(BigDecimal qty, I_C_UOM uom);

	/**
	 * Get Converted Qty from Server (no cache)
	 *
	 * @param qty The quantity to be converted
	 * @param uomFrom The C_UOM of the qty
	 * @param uomTo The targeted UOM
	 * @param useStdPrecision if true, standard precision, if false costing precision
	 * @return amount
	 * @deprecated should not be used
	 */
	@Deprecated
	BigDecimal convert(I_C_UOM uomFrom, I_C_UOM uomTo, BigDecimal qty, boolean useStdPrecision);

	/**
	 * * Converts the given qty from the given product's stocking UOM to the given destination UOM.
	 * <p>
	 * As a rule of thumb, if you want to get QtyEntered from QtOrdered/Moved/Invoiced/Requiered, this is your method.
	 *
	 * @param product product from whose stocking UOM we want to convert
	 * @param uomDest the UOM to which we want to convert
	 * @param qtyToConvert the Qty in the product's stocking UOM
	 * @return the converted qty or <code>null</code> if the product's stocking UOM is different from the given <code>uomDest</code> and if there is no conversion rate to use.
	 */
	BigDecimal convertFromProductUOM(ProductId productId, I_C_UOM uomDest, BigDecimal qtyToConvert);

	BigDecimal convertFromProductUOM(ProductId productId, UomId destUomId, BigDecimal qtyToConvert);

	/**
	 * Convert qty to target UOM and round.
	 *
	 * @return converted qty (std precision)
	 */
	Optional<BigDecimal> convert(I_C_UOM uomFrom, I_C_UOM uomTo, BigDecimal qty);

	/**
	 * Converts the given qty from the given source UOM to the given product's stocking UOM.
	 *
	 * @return the converted qty or <code>null</code> if the product's stocking UOM is different from the given <code>fromUomId</code> and if there is no conversion rate to use.
	 */
	BigDecimal convertToProductUOM(ProductId productId, BigDecimal qtyToConvert, UomId fromUomId);

	default BigDecimal convertToProductUOM(final ProductId productId, final I_C_UOM uomSource, final BigDecimal qtyToConvert)
	{
		final UomId fromUomId = uomSource != null ? UomId.ofRepoId(uomSource.getC_UOM_ID()) : null;
		return convertToProductUOM(productId, qtyToConvert, fromUomId);
	}

	Quantity convertToProductUOM(Quantity quantity, ProductId productId);

	Quantity computeSum(UOMConversionContext of, Collection<Quantity> quantities, UomId toUomId);

	ProductPrice convertProductPriceToUom(ProductPrice price, UomId toUomId, CurrencyPrecision pricePrecision);

}
