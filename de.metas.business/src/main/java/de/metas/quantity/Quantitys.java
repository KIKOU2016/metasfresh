package de.metas.quantity;

import java.io.IOException;
import java.math.BigDecimal;

import javax.annotation.Nullable;

import org.compiere.model.I_C_UOM;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import de.metas.product.IProductBL;
import de.metas.product.ProductId;
import de.metas.uom.IUOMConversionBL;
import de.metas.uom.IUOMDAO;
import de.metas.uom.UOMConversionContext;
import de.metas.uom.UomId;
import de.metas.util.Services;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/*
 * #%L
 * de.metas.business
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

@UtilityClass
public class Quantitys
{
	public Quantity create(@NonNull final BigDecimal qty, @NonNull final UomId uomId)
	{
		final IUOMDAO uomDao = Services.get(IUOMDAO.class);
		final I_C_UOM uomRecord = uomDao.getById(uomId);

		return Quantity.of(qty, uomRecord);
	}

	public Quantity create(
			@NonNull final BigDecimal qty, @NonNull final UomId uomId,
			@NonNull final BigDecimal sourceQty, @NonNull final UomId sourceUomId)
	{
		final IUOMDAO uomDao = Services.get(IUOMDAO.class);
		final I_C_UOM uomRecord = uomDao.getById(uomId);
		final I_C_UOM sourceUomRecord = uomDao.getById(sourceUomId);

		return new Quantity(qty, uomRecord, sourceQty, sourceUomRecord);
	}

	public Quantity createZero(@NonNull final UomId uomId)
	{
		final IUOMDAO uomDao = Services.get(IUOMDAO.class);
		final I_C_UOM uomRecord = uomDao.getById(uomId);

		return Quantity.zero(uomRecord);
	}

	public static Quantity createZero(@NonNull final ProductId productId)
	{
		final IProductBL productBL = Services.get(IProductBL.class);
		final I_C_UOM stockUomRecord = productBL.getStockUOM(productId);
	
		return Quantity.zero(stockUomRecord);
	}

	public Quantity create(@NonNull final BigDecimal qtyInStockUOM, @NonNull final ProductId productId)
	{
		return create(qtyInStockUOM, null/* nonStockUomId */, productId);
	}

	/**
	 * @param nonStockUomId optional; if not {@code null}, then {@code qtyInUOM} is also converted to the product's stock UOM.
	 */
	public Quantity create(
			@NonNull final BigDecimal qty,
			@Nullable final UomId nonStockUomId,
			@NonNull final ProductId productId)
	{
		final IProductBL productBL = Services.get(IProductBL.class);

		if (nonStockUomId == null)
		{
			final I_C_UOM stockUOMRecord = productBL.getStockUOM(productId);
			final Quantity stockQty = Quantity.of(qty, stockUOMRecord);
			return stockQty;
		}

		final UomId stockUomId = productBL.getStockUOMId(productId);
		final IUOMDAO uomDao = Services.get(IUOMDAO.class);
		final I_C_UOM nonStockUomRecord = uomDao.getById(nonStockUomId);

		final IUOMConversionBL uomConversionBL = Services.get(IUOMConversionBL.class);
		final Quantity stockQty = uomConversionBL.convertQuantityTo(Quantity.of(qty, nonStockUomRecord), UOMConversionContext.of(productId), stockUomId);
		return stockQty;
	}


	/**
	 * @return the sum of the given quantities; the result has the first augent's UOM; conversion is done as required.
	 */
	public Quantity add(
			@NonNull final UOMConversionContext conversionCtx,
			@NonNull final Quantity firstAugent,
			@NonNull final Quantity secondAugent)
	{
		final IUOMConversionBL uomConversionBL = Services.get(IUOMConversionBL.class);

		final Quantity secondAugentConverted = uomConversionBL.convertQuantityTo(secondAugent, conversionCtx, firstAugent.getUomId());

		return firstAugent.add(secondAugentConverted);
	}

	public Quantity subtract(
			@NonNull final UOMConversionContext conversionCtx,
			@NonNull final Quantity minuend,
			@NonNull final Quantity subtrahend)
	{
		final Quantity subtrahendConverted = create(subtrahend, conversionCtx, minuend.getUomId());

		return minuend.subtract(subtrahendConverted);
	}

	public Quantity create(
			@NonNull final Quantity qty,
			@NonNull final UOMConversionContext conversionCtx,
			@NonNull final UomId targedUomId)
	{
		final IUOMConversionBL uomConversionBL = Services.get(IUOMConversionBL.class);
		final Quantity converted = uomConversionBL.convertQuantityTo(qty, conversionCtx, targedUomId);

		return converted;
	}

	public class QuantityDeserializer extends StdDeserializer<Quantity>
	{
		private static final long serialVersionUID = -5406622853902102217L;

		public QuantityDeserializer()
		{
			super(Quantity.class);
		}

		@Override
		public Quantity deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException
		{
			JsonNode node = p.getCodec().readTree(p);
			final String qtyStr = node.get("qty").asText();
			final int uomRepoId = (Integer)((IntNode)node.get("uomId")).numberValue();

			final String sourceQtyStr;
			final int sourceUomRepoId;
			if (node.has("sourceQty"))
			{
				sourceQtyStr = node.get("sourceQty").asText();
				sourceUomRepoId = (Integer)((IntNode)node.get("sourceUomId")).numberValue();
			}
			else
			{
				sourceQtyStr = qtyStr;
				sourceUomRepoId = uomRepoId;
			}
			return Quantitys.create(
					new BigDecimal(qtyStr), UomId.ofRepoId(uomRepoId),
					new BigDecimal(sourceQtyStr), UomId.ofRepoId(sourceUomRepoId));
		}
	}

	public class QuantitySerializer extends StdSerializer<Quantity>
	{
		private static final long serialVersionUID = -8292209848527230256L;

		public QuantitySerializer()
		{
			super(Quantity.class);
		}

		@Override
		public void serialize(Quantity value, JsonGenerator gen, SerializerProvider provider) throws IOException
		{
			gen.writeStartObject();

			final String qtyStr = value.toBigDecimal().toString();
			final int uomId = value.getUomId().getRepoId();

			gen.writeFieldName("qty");
			gen.writeString(qtyStr);

			gen.writeFieldName("uomId");
			gen.writeNumber(uomId);

			final String sourceQtyStr = value.getSourceQty().toString();
			final int sourceUomId = value.getSourceUomId().getRepoId();

			if (!qtyStr.equals(sourceQtyStr) || uomId != sourceUomId)
			{
				gen.writeFieldName("sourceQty");
				gen.writeString(sourceQtyStr);

				gen.writeFieldName("sourceUomId");
				gen.writeNumber(sourceUomId);
			}
			gen.writeEndObject();
		}

	}
}
