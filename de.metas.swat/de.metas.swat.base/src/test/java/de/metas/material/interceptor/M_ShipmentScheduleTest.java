package de.metas.material.interceptor;

import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.save;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.adempiere.ad.modelvalidator.ModelChangeType;
import org.adempiere.mm.attributes.api.impl.ModelProductDescriptorExtractorUsingAttributeSetInstanceFactory;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.test.AdempiereTestHelper;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.adempiere.warehouse.WarehouseId;
import org.compiere.model.I_C_Order;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import de.metas.ShutdownListener;
import de.metas.StartupListener;
import de.metas.bpartner.BPartnerId;
import de.metas.inoutcandidate.model.I_M_ShipmentSchedule;
import de.metas.inoutcandidate.spi.ShipmentScheduleReferencedLine;
import de.metas.inoutcandidate.spi.ShipmentScheduleReferencedLineFactory;
import de.metas.inoutcandidate.spi.impl.ShipmentScheduleOrderReferenceProvider;
import de.metas.material.event.commons.OrderLineDescriptor;
import de.metas.material.event.shipmentschedule.AbstractShipmentScheduleEvent;
import de.metas.material.event.shipmentschedule.ShipmentScheduleCreatedEvent;
import de.metas.material.event.shipmentschedule.ShipmentScheduleDeletedEvent;
import de.metas.material.event.shipmentschedule.ShipmentScheduleUpdatedEvent;
import de.metas.shipping.ShipperId;
import lombok.NonNull;
import mockit.Expectations;
import mockit.Mocked;

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

@RunWith(SpringRunner.class)
@SpringBootTest(classes = { StartupListener.class, ShutdownListener.class,
		ModelProductDescriptorExtractorUsingAttributeSetInstanceFactory.class,

		// note that we partially mock this service in this test
		ShipmentScheduleReferencedLineFactory.class,

		// note that we won't test against this class. we just need one ShipmentScheduleReferencedLineProvider to be present,
		// so ShipmentScheduleReferencedLineFactory can be initialized by spring
		ShipmentScheduleOrderReferenceProvider.class
})
public class M_ShipmentScheduleTest
{
	@Mocked
	private ShipmentScheduleReferencedLineFactory shipmentScheduleReferencedLineFactory;

	private static final BPartnerId BPARTNER_ID1 = BPartnerId.ofRepoId(40);
	private static final BPartnerId BPARTNER_ID2 = BPartnerId.ofRepoId(45);

	private static final WarehouseId WAREHOUSE_ID = WarehouseId.ofRepoId(30);

	private static final int PRODUCT_ID = 20;

	private static final BigDecimal ONE = BigDecimal.ONE;
	private static final BigDecimal FOUR = new BigDecimal("4");
	private static final BigDecimal FIVE = new BigDecimal("5");
	private static final BigDecimal TEN = BigDecimal.TEN;
	private static final BigDecimal TWENTY = new BigDecimal("20");

	private I_M_ShipmentSchedule shipmentSchedule;

	private I_M_ShipmentSchedule oldShipmentSchedule;

	@Before
	public void init()
	{
		AdempiereTestHelper.get().init();

		oldShipmentSchedule = newInstance(I_M_ShipmentSchedule.class);
		oldShipmentSchedule.setQtyOrdered_Calculated(TWENTY); // note that setQtyOrdered is just for display!, QtyOrdered_Calculated one or QtyOrdered_Override is where the qty is!
		oldShipmentSchedule.setQtyReserved(FOUR);
		oldShipmentSchedule.setM_Product_ID(PRODUCT_ID);
		oldShipmentSchedule.setM_Warehouse_ID(WAREHOUSE_ID.getRepoId());
		oldShipmentSchedule.setC_BPartner_ID(BPARTNER_ID1.getRepoId());
		oldShipmentSchedule.setC_BPartner_Override_ID(BPARTNER_ID2.getRepoId());
		save(oldShipmentSchedule);

		shipmentSchedule = newInstance(I_M_ShipmentSchedule.class);
		shipmentSchedule.setQtyOrdered_Calculated(TEN); // decrease by ten
		shipmentSchedule.setQtyReserved(FIVE); // increase by one
		shipmentSchedule.setM_Product_ID(PRODUCT_ID);
		shipmentSchedule.setM_Warehouse_ID(WAREHOUSE_ID.getRepoId());
		shipmentSchedule.setC_BPartner_ID(BPARTNER_ID1.getRepoId());
		shipmentSchedule.setC_BPartner_Override_ID(BPARTNER_ID2.getRepoId());
		save(shipmentSchedule);

	}

	@Test
	public void createShipmentscheduleEvent_after_new()
	{
		final OrderLineDescriptor orderLineDescriptor = OrderLineDescriptor.builder()
				.orderBPartnerId(10)
				.orderId(20)
				.orderLineId(30)
				.build();
		setupShipmentScheduleReferencedLineFactory(orderLineDescriptor);

		final AbstractShipmentScheduleEvent result = M_ShipmentSchedule.INSTANCE
				.createShipmentScheduleEvent(shipmentSchedule, ModelChangeType.AFTER_NEW);

		assertThat(result).isNotNull();
		assertThat(result).isInstanceOf(ShipmentScheduleCreatedEvent.class);

		final ShipmentScheduleCreatedEvent createdEvent = (ShipmentScheduleCreatedEvent)result;
		assertThat(createdEvent.getShipmentScheduleId()).isEqualTo(shipmentSchedule.getM_ShipmentSchedule_ID());

		assertThat(createdEvent.getMaterialDescriptor().getCustomerId()).isEqualTo(BPARTNER_ID2);
		assertThat(createdEvent.getMaterialDescriptor().getQuantity()).isEqualByComparingTo(TEN);
		assertThat(createdEvent.getMaterialDescriptor().getProductId()).isEqualTo(PRODUCT_ID);
		assertThat(createdEvent.getMaterialDescriptor().getWarehouseId()).isEqualTo(WAREHOUSE_ID);
		assertThat(createdEvent.getReservedQuantity()).isEqualByComparingTo(FIVE);
		assertThat(createdEvent.getDocumentLineDescriptor()).isEqualTo(orderLineDescriptor);
	}

	/**
	 * Make sure that {@link ShipmentScheduleReferencedLineFactory} will return a {@link ShipmentScheduleReferencedLine}
	 * that contains the given {@code orderLineDescriptor}.
	 *
	 * @param orderLineDescriptor
	 */
	private void setupShipmentScheduleReferencedLineFactory(@NonNull final OrderLineDescriptor orderLineDescriptor)
	{
		final ShipmentScheduleReferencedLine shipmentScheduleReferencedLine = //
				ShipmentScheduleReferencedLine.builder()
						.recordRef(TableRecordReference.of(I_C_Order.Table_Name, 10))
						.shipperId(ShipperId.optionalOfRepoId(20))
						.warehouseId(WAREHOUSE_ID)
						.documentLineDescriptor(orderLineDescriptor).build();
		// @formatter:off
		new Expectations(ShipmentScheduleReferencedLineFactory.class)
		{{
			shipmentScheduleReferencedLineFactory.createFor(shipmentSchedule);
			result = shipmentScheduleReferencedLine;
		}};	// @formatter:on
	}

	@Test
	public void createShipmentscheduleEvent_after_change()
	{
		// @formatter:off
		new Expectations(InterfaceWrapperHelper.class) {{

			InterfaceWrapperHelper.createOld(shipmentSchedule, I_M_ShipmentSchedule.class);
			times = 1; result = oldShipmentSchedule;
		}}; // @formatter:on

		final AbstractShipmentScheduleEvent result = M_ShipmentSchedule.INSTANCE
				.createShipmentScheduleEvent(shipmentSchedule, ModelChangeType.AFTER_CHANGE);

		assertThat(result).isNotNull();
		assertThat(result).isInstanceOf(ShipmentScheduleUpdatedEvent.class);

		final ShipmentScheduleUpdatedEvent updatedEvent = (ShipmentScheduleUpdatedEvent)result;
		assertThat(updatedEvent.getShipmentScheduleId()).isEqualTo(shipmentSchedule.getM_ShipmentSchedule_ID());
		assertThat(updatedEvent.getMaterialDescriptor().getCustomerId()).isEqualTo(BPARTNER_ID2);
		assertThat(updatedEvent.getMaterialDescriptor().getQuantity()).isEqualByComparingTo(TEN);
		assertThat(updatedEvent.getMaterialDescriptor().getProductId()).isEqualTo(PRODUCT_ID);
		assertThat(updatedEvent.getMaterialDescriptor().getWarehouseId()).isEqualTo(WAREHOUSE_ID);
		assertThat(updatedEvent.getReservedQuantity()).isEqualByComparingTo(FIVE);
		assertThat(updatedEvent.getReservedQuantityDelta()).isEqualByComparingTo(ONE);
		assertThat(updatedEvent.getOrderedQuantityDelta()).isEqualByComparingTo(TEN.negate());
	}

	@Test
	public void createShipmentscheduleEvent_before_delete()
	{
		final AbstractShipmentScheduleEvent result = M_ShipmentSchedule.INSTANCE
				.createShipmentScheduleEvent(shipmentSchedule, ModelChangeType.BEFORE_DELETE);

		assertThat(result).isNotNull();
		assertThat(result).isInstanceOf(ShipmentScheduleDeletedEvent.class);

		final ShipmentScheduleDeletedEvent deletedEvent = (ShipmentScheduleDeletedEvent)result;
		assertThat(deletedEvent.getShipmentScheduleId()).isEqualTo(shipmentSchedule.getM_ShipmentSchedule_ID());
		assertThat(deletedEvent.getMaterialDescriptor().getCustomerId()).isEqualTo(BPARTNER_ID2);
		assertThat(deletedEvent.getMaterialDescriptor().getQuantity()).isEqualByComparingTo(TEN);
		assertThat(deletedEvent.getMaterialDescriptor().getProductId()).isEqualTo(PRODUCT_ID);
		assertThat(deletedEvent.getMaterialDescriptor().getWarehouseId()).isEqualTo(WAREHOUSE_ID);
		assertThat(deletedEvent.getReservedQuantity()).isEqualByComparingTo(FIVE);
	}
}
