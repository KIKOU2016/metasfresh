package de.metas.inoutcandidate.spi.impl;

/*
 * #%L
 * de.metas.swat.base
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */


import java.math.BigDecimal;

import org.adempiere.util.test.AbstractExpectation;
import org.adempiere.util.test.ErrorMessage;

import de.metas.util.Check;

public class ReceiptQtyExpectation<ParentExpectationType> extends AbstractExpectation<ParentExpectationType>
{
	public static ReceiptQtyExpectation<Object> newInstance()
	{
		return new ReceiptQtyExpectation<>(null);
	}

	// Expectations
	private Integer qtyPrecision = null;
	private BigDecimal qty;
	private BigDecimal qtyWithIssues;
	private BigDecimal qtyWithIssuesExact;
	private BigDecimal qtyWithoutIssues;
	private BigDecimal qualityDiscountPercent;
	private String qualityNotices;
	private boolean qualityNoticesSet;

	public ReceiptQtyExpectation(final ParentExpectationType parentExpectation)
	{
		super(parentExpectation);
	}

	public ReceiptQtyExpectation<ParentExpectationType> copyFrom(final ReceiptQtyExpectation<?> from)
	{
		this.qty = from.qty;
		this.qtyWithIssues = from.qtyWithIssues;
		this.qtyWithoutIssues = from.qtyWithoutIssues;
		this.qualityDiscountPercent = from.qualityDiscountPercent;
		this.qtyPrecision = from.qtyPrecision;
		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> assertExpected(ReceiptQty qtyAndQuality)
	{
		return assertExpected(newErrorMessage(), qtyAndQuality);
	}

	public ReceiptQtyExpectation<ParentExpectationType> assertExpected(final String message, ReceiptQty qtyAndQuality)
	{
		return assertExpected(newErrorMessage(message), qtyAndQuality);
	}

	public ReceiptQtyExpectation<ParentExpectationType> assertExpected(final ErrorMessage message, ReceiptQty qtyAndQuality)
	{
		final ErrorMessage messageToUse = derive(message)
				.addContextInfo(qtyAndQuality);

		assertNotNull(messageToUse.expect("qtyAndQuality shall not be null"), qtyAndQuality);

		if (qty != null)
		{
			assertEquals(messageToUse.expect("qty"), this.qty, qtyAndQuality.getQtyTotal());
		}
		if (qtyWithIssues != null)
		{
			final int qtyPrecision = getQtyPrecisionToUse();
			assertEquals(messageToUse.expect("qtyWithIssues"), this.qtyWithIssues, qtyAndQuality.getQtyWithIssues(qtyPrecision));
		}
		if (qtyWithIssuesExact != null)
		{
			assertEquals(messageToUse.expect("qtyWithIssues"), this.qtyWithIssuesExact, qtyAndQuality.getQtyWithIssuesExact());
		}
		if (qtyWithoutIssues != null)
		{
			final int qtyPrecision = getQtyPrecisionToUse();
			assertEquals(messageToUse.expect("qtyWithoutIssues"), this.qtyWithoutIssues, qtyAndQuality.getQtyWithoutIssues(qtyPrecision));
		}
		if (qualityDiscountPercent != null)
		{
			assertEquals(messageToUse.expect("qualityDiscountPercent"), this.qualityDiscountPercent, qtyAndQuality.getQualityDiscountPercent());
		}

		if (qualityNoticesSet)
		{
			assertEquals(messageToUse.expect("qualityNotices"), this.qualityNotices, qtyAndQuality.getQualityNotices().asQualityNoticesString());
		}

		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qty(final BigDecimal qty)
	{
		this.qty = qty;
		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qty(final String qty)
	{
		return qty(new BigDecimal(qty));
	}

	public ReceiptQtyExpectation<ParentExpectationType> qty(final int qty)
	{
		return qty(new BigDecimal(qty));
	}

	public BigDecimal getQty()
	{
		return qty;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qtyWithIssues(final BigDecimal qtyWithIssues)
	{
		this.qtyWithIssues = qtyWithIssues;
		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qtyWithIssues(final String qtyWithIssues)
	{
		return qtyWithIssues(new BigDecimal(qtyWithIssues));
	}

	public BigDecimal getQtyWithIssues()
	{
		return qtyWithIssues;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qtyWithIssuesExact(final BigDecimal qtyWithIssuesExact)
	{
		this.qtyWithIssuesExact = qtyWithIssuesExact;
		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qtyWithIssuesExact(final String qtyWithIssuesExact)
	{
		return qtyWithIssuesExact(new BigDecimal(qtyWithIssuesExact));
	}

	public BigDecimal getQtyWithIssuesExact()
	{
		return qtyWithIssuesExact;
	}


	public ReceiptQtyExpectation<ParentExpectationType> qtyWithoutIssues(final BigDecimal qtyWithoutIssues)
	{
		this.qtyWithoutIssues = qtyWithoutIssues;
		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qtyWithoutIssues(final String qtyWithoutIssues)
	{
		return qtyWithoutIssues(new BigDecimal(qtyWithoutIssues));
	}

	public BigDecimal getQtyWithoutIssues()
	{
		return qtyWithoutIssues;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qualityDiscountPercent(final BigDecimal qualityDiscountPercent)
	{
		this.qualityDiscountPercent = qualityDiscountPercent;
		return this;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qualityDiscountPercent(final String qualityDiscountPercent)
	{
		return qualityDiscountPercent(new BigDecimal(qualityDiscountPercent));
	}

	public ReceiptQtyExpectation<ParentExpectationType> qualityDiscountPercent(final int qualityDiscountPercent)
	{
		return qualityDiscountPercent(new BigDecimal(qualityDiscountPercent));
	}

	public BigDecimal getQualityDiscountPercent()
	{
		return qualityDiscountPercent;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qtyPrecision(final int qtyPrecision)
	{
		this.qtyPrecision = qtyPrecision;
		return this;
	}

	public int getQtyPrecisionToUse()
	{
		Check.assumeNotNull(qtyPrecision, "qty precision shall be configured");
		return qtyPrecision;
	}

	public ReceiptQtyExpectation<ParentExpectationType> qualityNotices(final String qualityNotices)
	{
		this.qualityNotices = qualityNotices;
		this.qualityNoticesSet = true;
		return this;
	}

}
