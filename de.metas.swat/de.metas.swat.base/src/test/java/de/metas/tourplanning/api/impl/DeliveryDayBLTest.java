package de.metas.tourplanning.api.impl;

import java.time.LocalDate;
import java.time.ZonedDateTime;

import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.lang.IContextAware;
import org.junit.Assert;
import org.junit.Test;

import de.metas.bpartner.BPartnerLocationId;
import de.metas.lang.SOTrx;
import de.metas.tourplanning.TourPlanningTestBase;

/*
 * #%L
 * de.metas.swat.base
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

public class DeliveryDayBLTest extends TourPlanningTestBase
{

	@Override
	protected void afterInit()
	{
		tour = createTour("tour01");
		tourVersion = createTourVersion(tour, LocalDate.of(2017, 1, 1));
		bpartner = createBPartner("bp1");
		bpLocation = createBPLocation(bpartner);
	}

	// task 1211
	// test for void de.metas.tourplanning.api.impl.DeliveryDayBL.calculatePreparationDateOrNull(IContextAware, boolean, Timestamp, int)
	@Test
	public void test_calculatePreparationDateOrNull()
	{
		final IContextAware ctx = InterfaceWrapperHelper.getContextAware(tour);
		final BPartnerLocationId bpartnerLocationId = BPartnerLocationId.ofRepoId(bpLocation.getC_BPartner_ID(), bpLocation.getC_BPartner_Location_ID());

		createDeliveryDay("27.03.2017 11:30:00.000", 4);
		createDeliveryDay("28.03.2017 11:30:00.000", 4);
		createDeliveryDay("29.03.2017 11:30:00.000", 4);
		createDeliveryDay("30.03.2017 11:30:00.000", 4);
		createDeliveryDay("31.03.2017 11:30:00.000", 4);
		createDeliveryDay("01.04.2017 11:30:00.000", 4);
		createDeliveryDay("02.04.2017 11:30:00.000", 4);

		createDeliveryDay("27.03.2017 18:30:00.000", 4);
		createDeliveryDay("28.03.2017 18:30:00.000", 4);
		createDeliveryDay("29.03.2017 18:30:00.000", 4);
		createDeliveryDay("30.03.2017 18:30:00.000", 4);
		createDeliveryDay("31.03.2017 18:30:00.000", 4);
		createDeliveryDay("01.04.2017 18:30:00.000", 4);
		createDeliveryDay("02.04.2017 18:30:00.000", 4);

		// 27.03.2017 23:59h, dateOrdered 27.03.2017 11:29h
		final ZonedDateTime dateOrdered1 = toZonedDateTime("27.03.2017 11:29:00.000");
		final ZonedDateTime datePromised1 = toZonedDateTime("27.03.2017 23:59:00.000");
		final ZonedDateTime preparationDate1 = deliveryDayBL.calculatePreparationDateOrNull(ctx, SOTrx.SALES, dateOrdered1, datePromised1, bpartnerLocationId);
		final ZonedDateTime expected1 = toZonedDateTime("27.03.2017 11:30:00.000");

		Assert.assertEquals(preparationDate1, expected1);

		// 27.03.2017 23:59h, dateOrdered 27.03.2017 11:31h
		final ZonedDateTime dateOrdered2 = toZonedDateTime("27.03.2017 11:31:00.000");
		final ZonedDateTime datePromised2 = toZonedDateTime("27.03.2017 23:59:00.000");
		final ZonedDateTime preparationDate2 = deliveryDayBL.calculatePreparationDateOrNull(ctx, SOTrx.SALES, dateOrdered2, datePromised2, bpartnerLocationId);
		final ZonedDateTime expected2 = toZonedDateTime("27.03.2017 18:30:00.000");

		Assert.assertEquals(preparationDate2, expected2);

		// 02.04.2017 23:59h, dateOrdered 27.03.2017 11:29h
		final ZonedDateTime dateOrdered3 = toZonedDateTime("27.03.2017 11:29:00.000");
		final ZonedDateTime datePromised3 = toZonedDateTime("02.04.2017 23:59:00.000");
		final ZonedDateTime preparationDate3 = deliveryDayBL.calculatePreparationDateOrNull(ctx, SOTrx.SALES, dateOrdered3, datePromised3, bpartnerLocationId);
		final ZonedDateTime expected3 = toZonedDateTime("02.04.2017 11:30:00.000");

		Assert.assertEquals(preparationDate3, expected3);

	}

}
