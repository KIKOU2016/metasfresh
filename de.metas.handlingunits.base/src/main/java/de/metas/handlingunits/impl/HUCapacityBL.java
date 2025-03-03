package de.metas.handlingunits.impl;

/*
 * #%L
 * de.metas.handlingunits.base
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
import java.util.Date;

import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;

import de.metas.handlingunits.IHUCapacityBL;
import de.metas.handlingunits.IHUPIItemProductBL;
import de.metas.handlingunits.IHUPIItemProductDAO;
import de.metas.handlingunits.exceptions.HUException;
import de.metas.handlingunits.model.I_M_HU_Item;
import de.metas.handlingunits.model.I_M_HU_PI_Item_Product;
import de.metas.product.IProductBL;
import de.metas.product.ProductId;
import de.metas.quantity.Capacity;
import de.metas.quantity.CapacityInterface;
import de.metas.uom.IUOMConversionBL;
import de.metas.util.Check;
import de.metas.util.Services;
import lombok.NonNull;

public class HUCapacityBL implements IHUCapacityBL
{

	@Override
	public Capacity getCapacity(final I_M_HU_PI_Item_Product itemDefProduct, final I_M_Product product, final I_C_UOM uom)
	{
		final ProductId productId = product != null ? ProductId.ofRepoId(product.getM_Product_ID()) : null;
		return getCapacity(itemDefProduct, productId, uom);
	}

	@Override
	public Capacity getCapacity(final I_M_HU_PI_Item_Product itemDefProduct, final ProductId productId, final I_C_UOM uom)
	{
		final ProductId productToUseId;
		if (itemDefProduct.isAllowAnyProduct())
		{
			Check.assumeNotNull(productId, "productId not null");
			productToUseId = productId;
		}
		else
		{
			final ProductId piipProductId = ProductId.ofRepoIdOrNull(itemDefProduct.getM_Product_ID());
			if (productId == null)
			{
				productToUseId = piipProductId;
				if (productToUseId == null)
				{
					// Case: product was not found in PI_Item_Product nor was given was parameter
					throw new HUException("@NotFound@ @M_Product_ID@: " + itemDefProduct);
				}
			}
			else
			{
				if (piipProductId != null && !ProductId.equals(piipProductId, productId))
				{
					final IProductBL productBL = Services.get(IProductBL.class);
					final String productName = productBL.getProductValueAndName(productId);
					final String piipProductName = productBL.getProductValueAndName(piipProductId);
					throw new HUException("CU-TU assignment "
							+ "\n@M_HU_PI_Item_Product_ID@: " + itemDefProduct.getDescription() + " (" + I_M_HU_PI_Item_Product.COLUMNNAME_M_HU_PI_Item_Product_ID + "=" + itemDefProduct.getM_HU_PI_Item_Product_ID() + ") "
							+ "\n@M_HU_PI_Item_Product_ID@ - @M_Product_ID@: " + piipProductName
							+ "\nis not compatible with required product @M_Product_ID@: " + productName + "( " + I_M_Product.COLUMNNAME_M_Product_ID + "=" + productId + ")");
				}

				productToUseId = productId;
			}
		}

		Check.assumeNotNull(productToUseId, "productToUseId not null");

		final boolean infiniteCapacity = isInfiniteCapacity(itemDefProduct);
		if (infiniteCapacity)
		{
			return Capacity.createInfiniteCapacity(productToUseId, uom);
		}

		final BigDecimal qty = itemDefProduct.getQty();
		final I_C_UOM qtyUOM = IHUPIItemProductBL.extractUOMOrNull(itemDefProduct);
		final BigDecimal qtyConv = Services.get(IUOMConversionBL.class)
				.convertQty(productToUseId, qty, qtyUOM, uom);

		final boolean allowNegativeCapacity = false;

		return Capacity.createCapacity(qtyConv, productToUseId, uom, allowNegativeCapacity);
	}

	@Override
	public CapacityInterface getCapacity(
			@NonNull final I_M_HU_Item huItem,
			final ProductId productId,
			final I_C_UOM uom,
			final Date date)
	{
		final I_M_HU_PI_Item_Product itemDefProduct = Services.get(IHUPIItemProductDAO.class).retrievePIMaterialItemProduct(huItem, productId, date);
		if (itemDefProduct == null)
		{
			final boolean allowNegativeCapacity = false;
			return Capacity.createZeroCapacity(productId, uom, allowNegativeCapacity);
		}

		return getCapacity(itemDefProduct, productId, uom);
	}

	@Override
	public CapacityInterface getCapacity(
			@NonNull final I_M_HU_Item huItem,
			final I_M_Product product,
			final I_C_UOM uom,
			final Date date)
	{
		final ProductId productId = ProductId.ofRepoIdOrNull(product != null ? product.getM_Product_ID() : -1);
		return getCapacity(huItem, productId, uom, date);
	}

	@Override
	public boolean isInfiniteCapacity(final I_M_HU_PI_Item_Product itemDefProduct)
	{
		return itemDefProduct.isInfiniteCapacity();
	}

	@Override
	public boolean isValidItemProduct(final I_M_HU_PI_Item_Product itemDefProduct)
	{
		Check.assumeNotNull(itemDefProduct, "Error: Null record");
		// item which allows any product should have infinite capacity and no product assigned
		if (itemDefProduct.isAllowAnyProduct() && (itemDefProduct.getM_Product_ID() > 0 || !isInfiniteCapacity(itemDefProduct)))
		{
			return false;
		}

		if (isInfiniteCapacity(itemDefProduct))
		{
			// Infinite capacity item product : valid
			return true;
		}

		// If it's not infinite capacity, quantity must be > 0
		return itemDefProduct.getQty().signum() > 0;
	}
}
