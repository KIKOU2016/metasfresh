package de.metas.handlingunits.inventory.impl;

import static org.adempiere.model.InterfaceWrapperHelper.getTableId;
import static org.adempiere.model.InterfaceWrapperHelper.newInstance;
import static org.adempiere.model.InterfaceWrapperHelper.save;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.adempiere.service.ISysConfigBL;
import org.adempiere.test.AdempiereTestWatcher;
import org.compiere.model.I_C_Activity;
import org.compiere.model.I_C_DocType;
import org.compiere.model.I_C_UOM;
import org.compiere.model.I_M_Product;
import org.compiere.model.I_M_Warehouse;
import org.compiere.model.X_C_DocType;
import org.compiere.model.X_M_InOut;
import org.compiere.model.X_M_Inventory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;

import com.google.common.collect.ImmutableList;

import de.metas.handlingunits.HUTestHelper;
import de.metas.handlingunits.HUTestHelper.TestHelperLoadRequest;
import de.metas.handlingunits.IHUPackingMaterialsCollector;
import de.metas.handlingunits.IHUStatusBL;
import de.metas.handlingunits.IHandlingUnitsBL;
import de.metas.handlingunits.IHandlingUnitsDAO;
import de.metas.handlingunits.IMutableHUContext;
import de.metas.handlingunits.allocation.impl.HUProducerDestination;
import de.metas.handlingunits.allocation.transfer.impl.LUTUProducerDestination;
import de.metas.handlingunits.allocation.transfer.impl.LUTUProducerDestinationTestSupport;
import de.metas.handlingunits.inventory.IHUInventoryBL;
import de.metas.handlingunits.model.I_M_HU;
import de.metas.handlingunits.model.I_M_HU_Assignment;
import de.metas.handlingunits.model.I_M_InOut;
import de.metas.handlingunits.model.I_M_InOutLine;
import de.metas.handlingunits.model.I_M_Inventory;
import de.metas.handlingunits.model.I_M_InventoryLine;
import de.metas.handlingunits.model.I_M_Locator;
import de.metas.handlingunits.model.X_M_HU;
import de.metas.handlingunits.model.validator.M_HU;
import de.metas.handlingunits.spi.IHUPackingMaterialCollectorSource;
import de.metas.inventory.IInventoryDAO;
import de.metas.inventory.InventoryId;
import de.metas.inventory.impl.InventoryBL;
import de.metas.product.ProductId;
import de.metas.product.acct.api.ActivityId;
import de.metas.util.Services;
import de.metas.util.time.FixedTimeSource;
import de.metas.util.time.SystemTime;
import lombok.NonNull;
import mockit.Mocked;

/*
 * #%L
 * de.metas.handlingunits.base
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

/**
 * This test doesn'T really work as it tests nothing. See the {@link #test()} method.
 * It was added to identify bugs in a different person's issue, but the problems were solved by manual testing before I could get to finish this.
 * Feel free to fix and extend it.
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
public class HUInternalUseInventoryProducerTests
{
	/** Watches the current tests and dumps the database to console in case of failure */
	@Rule
	public final TestWatcher testWatcher = new AdempiereTestWatcher();

	private LUTUProducerDestinationTestSupport data;

	@Mocked
	private IHUPackingMaterialsCollector<IHUPackingMaterialCollectorSource> noopPackingMaterialsCollector;

	private IHandlingUnitsBL handlingUnitsBL;
	private IHUStatusBL huStatusBL;
	private IHandlingUnitsDAO handlingUnitsDAO;
	private IHUInventoryBL huInventoryBL;
	private IInventoryDAO inventoryDAO;

	private I_M_Locator locator;

	@Before
	public void init()
	{
		data = new LUTUProducerDestinationTestSupport();

		handlingUnitsBL = Services.get(IHandlingUnitsBL.class);
		handlingUnitsDAO = Services.get(IHandlingUnitsDAO.class);
		huStatusBL = Services.get(IHUStatusBL.class);
		huInventoryBL = Services.get(IHUInventoryBL.class);
		inventoryDAO = Services.get(IInventoryDAO.class);

		final I_C_DocType dt = newInstance(I_C_DocType.class);
		dt.setDocBaseType(X_C_DocType.DOCBASETYPE_MaterialPhysicalInventory);
		dt.setDocSubType(X_C_DocType.DOCSUBTYPE_InternalUseInventory);
		save(dt);

		final I_M_Warehouse wh = newInstance(I_M_Warehouse.class);
		save(wh);

		locator = newInstance(I_M_Locator.class);
		locator.setM_Warehouse(wh);
		save(locator);

		Services.get(ISysConfigBL.class).setValue(InventoryBL.SYSCONFIG_QuickInput_Charge_ID, 1234, 0);

		SystemTime.setTimeSource(new FixedTimeSource(2019, 6, 10, 10, 00, 00));
	}

	@Test
	public void test_CreateInventories()
	{
		final I_M_HU lu = mkAggregateCUs("50", 10);

		final List<I_M_Inventory> inventories = HUInternalUseInventoryProducer.newInstance()
				.addHUs(ImmutableList.of(lu))
				.createInventories();

		assertThat(inventories.size()).isEqualTo(1);

		final InventoryId inventoryId = InventoryId.ofRepoId(inventories.get(0).getM_Inventory_ID());

		final List<I_M_InventoryLine> linesForInventoryId = inventoryDAO.retrieveLinesForInventoryId(inventoryId, I_M_InventoryLine.class);

		assertThat(linesForInventoryId.size()).isEqualTo(1);
	}

	@Test
	public void moveToGarbage_NonReceipt_HUs()
	{
		final I_M_HU lu = mkAggregateCUs("50", 10);

		final I_M_HU cuWithTU = mkRealCUWithTUToSplit("10");
		final I_M_HU topLevelParentTU = handlingUnitsBL.getTopLevelParent(cuWithTU);

		final I_M_HU cu = mkRealStandAloneCUToSplit("15");

		Collection<I_M_HU> husToDestroy = new ArrayList<>();

		husToDestroy.add(cu);
		husToDestroy.add(topLevelParentTU);
		husToDestroy.add(lu);

		final I_C_Activity activity = createActivity("Activity1");
		final ActivityId activityId = ActivityId.ofRepoIdOrNull(activity.getC_Activity_ID());

		final Timestamp movementDate = SystemTime.asDayTimestamp();

		final String description = "Test Description";

		final boolean isCompleteInventory = true;

		final boolean isCreateMovement = false;

		final List<I_M_Inventory> inventories = huInventoryBL.moveToGarbage(husToDestroy, movementDate, activityId, description, isCompleteInventory, isCreateMovement);
		assertThat(inventories.size()).isEqualTo(1);

		final I_M_Inventory inventory = inventories.get(0);

		assertThat(inventory.getC_Activity()).isEqualTo(activity);
		assertThat(inventory.getDescription()).isEqualTo(description);
		assertThat(inventory.getMovementDate()).isEqualTo(movementDate);
		assertThat(inventory.getDocStatus()).isEqualTo(X_M_Inventory.DOCSTATUS_Completed);

		final InventoryId inventoryId = InventoryId.ofRepoId(inventories.get(0).getM_Inventory_ID());

		final List<I_M_InventoryLine> linesForInventoryId = inventoryDAO.retrieveLinesForInventoryId(inventoryId, I_M_InventoryLine.class);

		assertThat(linesForInventoryId.size()).isEqualTo(3);

		final I_M_InventoryLine luInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == lu.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(luInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(luInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(luInventoryLine.getQtyInternalUse()).isEqualByComparingTo(new BigDecimal(50));

		final I_M_InventoryLine cuWithTUInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == topLevelParentTU.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(cuWithTUInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(cuWithTUInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(cuWithTUInventoryLine.getQtyInternalUse()).isEqualByComparingTo(BigDecimal.TEN);

		final I_M_InventoryLine cuInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == cu.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(cuInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(cuInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(cuInventoryLine.getQtyInternalUse()).isEqualByComparingTo(new BigDecimal(15));
	}

	@Test
	public void moveToGarbage_Receipt_HUs()
	{

		final I_M_InOutLine inoutLine = createInOutLine(data.helper.pTomato, data.helper.uomKg, BigDecimal.TEN);
		final I_M_HU receiptLu = mkAggregateCUs("50", 10);

		createHUAssignment(inoutLine, receiptLu);

		Collection<I_M_HU> husToDestroy = new ArrayList<>();

		husToDestroy.add(receiptLu);

		final I_C_Activity activity = createActivity("Activity1");
		final ActivityId activityId = ActivityId.ofRepoIdOrNull(activity.getC_Activity_ID());

		final Timestamp movementDate = SystemTime.asDayTimestamp();

		final String description = "Test Description";

		final boolean isCompleteInventory = true;

		final boolean isCreateMovement = false;

		final List<I_M_Inventory> inventories = huInventoryBL.moveToGarbage(husToDestroy, movementDate, activityId, description, isCompleteInventory, isCreateMovement);
		assertThat(inventories.size()).isEqualTo(1);

		final I_M_Inventory inventory = inventories.get(0);

		assertThat(inventory.getC_Activity()).isEqualTo(activity);
		assertThat(inventory.getDescription()).isEqualTo(description);
		assertThat(inventory.getMovementDate()).isEqualTo(movementDate);
		assertThat(inventory.getDocStatus()).isEqualTo(X_M_Inventory.DOCSTATUS_Completed);

		final InventoryId inventoryId = InventoryId.ofRepoId(inventory.getM_Inventory_ID());

		final List<I_M_InventoryLine> linesForInventoryId = inventoryDAO.retrieveLinesForInventoryId(inventoryId, I_M_InventoryLine.class);

		assertThat(linesForInventoryId.size()).isEqualTo(1);

		final I_M_InventoryLine inventoryLine = linesForInventoryId.get(0);

		assertThat(inventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(inventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(inventoryLine.getQtyInternalUse()).isEqualByComparingTo(new BigDecimal(50));

	}

	@Test
	public void moveToGarbage_Mixt_HUs()
	{
		final I_M_InOutLine inoutLine = createInOutLine(data.helper.pTomato, data.helper.uomKg, BigDecimal.TEN);
		final I_M_HU receiptLu = mkAggregateCUs("50", 10);

		createHUAssignment(inoutLine, receiptLu);

		final I_M_HU lu = mkAggregateCUs("50", 10);

		final I_M_HU cuWithTU = mkRealCUWithTUToSplit("10");
		final I_M_HU topLevelParentTU = handlingUnitsBL.getTopLevelParent(cuWithTU);

		final I_M_HU cu = mkRealStandAloneCUToSplit("15");

		Collection<I_M_HU> husToDestroy = new ArrayList<>();

		husToDestroy.add(cu);
		husToDestroy.add(topLevelParentTU);
		husToDestroy.add(lu);
		husToDestroy.add(receiptLu);

		final I_C_Activity activity = createActivity("Activity1");
		final ActivityId activityId = ActivityId.ofRepoIdOrNull(activity.getC_Activity_ID());

		final Timestamp movementDate = SystemTime.asDayTimestamp();

		final String description = "Test Description";

		final boolean isCompleteInventory = true;

		final boolean isCreateMovement = false;

		final List<I_M_Inventory> inventories = huInventoryBL.moveToGarbage(husToDestroy, movementDate, activityId, description, isCompleteInventory, isCreateMovement);
		assertThat(inventories.size()).isEqualTo(1);

		final I_M_Inventory inventory = inventories.get(0);

		assertThat(inventory.getC_Activity()).isEqualTo(activity);
		assertThat(inventory.getDescription()).isEqualTo(description);
		assertThat(inventory.getMovementDate()).isEqualTo(movementDate);
		assertThat(inventory.getDocStatus()).isEqualTo(X_M_Inventory.DOCSTATUS_Completed);

		final InventoryId inventoryId = InventoryId.ofRepoId(inventory.getM_Inventory_ID());

		final List<I_M_InventoryLine> linesForInventoryId = inventoryDAO.retrieveLinesForInventoryId(inventoryId, I_M_InventoryLine.class);

		assertThat(linesForInventoryId.size()).isEqualTo(4);

		final I_M_InventoryLine luInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == lu.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(luInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(luInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(luInventoryLine.getQtyInternalUse()).isEqualByComparingTo(new BigDecimal(50));

		final I_M_InventoryLine cuWithTUInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == topLevelParentTU.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(cuWithTUInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(cuWithTUInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(cuWithTUInventoryLine.getQtyInternalUse()).isEqualByComparingTo(BigDecimal.TEN);

		final I_M_InventoryLine cuInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == cu.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(cuInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(cuInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(cuInventoryLine.getQtyInternalUse()).isEqualByComparingTo(new BigDecimal(15));

		final I_M_InventoryLine receiptInventoryLine = linesForInventoryId.stream()
				.filter(inventoryLine -> inventoryLine.getM_HU_ID() == receiptLu.getM_HU_ID())
				.findFirst()
				.get();

		assertThat(receiptInventoryLine.getM_Product_ID()).isEqualTo(data.helper.pTomato.getM_Product_ID());
		assertThat(receiptInventoryLine.getC_UOM_ID()).isEqualTo(data.helper.uomKg.getC_UOM_ID());
		assertThat(receiptInventoryLine.getQtyInternalUse()).isEqualByComparingTo(new BigDecimal(50));

	}

	private I_M_HU_Assignment createHUAssignment(final I_M_InOutLine inoutLine, final I_M_HU hu)
	{
		final I_M_HU_Assignment assignment = newInstance(I_M_HU_Assignment.class);
		assignment.setAD_Table_ID(getTableId(I_M_InOutLine.class));
		assignment.setRecord_ID(inoutLine.getM_InOutLine_ID());
		assignment.setM_HU(hu);

		save(assignment);

		return assignment;
	}

	private I_M_InOutLine createInOutLine(final I_M_Product product, final I_C_UOM uom, final BigDecimal qty)
	{
		final I_M_InOut inout = newInstance(I_M_InOut.class);
		inout.setMovementDate(SystemTime.asDayTimestamp());
		inout.setDocStatus(X_M_InOut.DOCSTATUS_Completed);
		save(inout);

		final I_M_InOutLine inoutLine = newInstance(I_M_InOutLine.class);
		inoutLine.setM_InOut(inout);
		inoutLine.setM_Product_ID(product.getM_Product_ID());
		inoutLine.setQtyEntered(qty);
		inoutLine.setC_UOM_ID(uom.getC_UOM_ID());
		inoutLine.setM_Locator_ID(locator.getM_Locator_ID());

		save(inoutLine);
		return inoutLine;
	}

	private I_C_Activity createActivity(final String name)
	{
		final I_C_Activity activity = newInstance(I_C_Activity.class);
		activity.setName(name);
		save(activity);

		return activity;
	}

	/**
	 * Creates an LU with one aggregate HU. Both the LU's and aggregate HU's status is "active".
	 *
	 * @param totalQtyCUStr
	 * @param qtyCUsPerTU
	 * @return
	 */
	public I_M_HU mkAggregateCUs(
			@NonNull final String totalQtyCUStr,
			final int qtyCUsPerTU)
	{
		final ProductId cuProductId = data.helper.pTomatoProductId;
		final I_C_UOM cuUOM = data.helper.uomKg;
		final BigDecimal totalQtyCU = new BigDecimal(totalQtyCUStr);

		final LUTUProducerDestination lutuProducer = new LUTUProducerDestination();
		lutuProducer.setLUItemPI(data.piLU_Item_IFCO);
		lutuProducer.setLUPI(data.piLU);
		lutuProducer.setTUPI(data.piTU_IFCO);
		lutuProducer.setMaxTUsPerLU(Integer.MAX_VALUE); // allow as many TUs on that one pallet as we want

		// Custom TU capacity (if specified)
		if (qtyCUsPerTU > 0)
		{
			lutuProducer.addCUPerTU(cuProductId, BigDecimal.valueOf(qtyCUsPerTU), cuUOM);
		}

		final TestHelperLoadRequest loadRequest = HUTestHelper.TestHelperLoadRequest.builder()
				.producer(lutuProducer)
				.cuProductId(cuProductId)
				.loadCuQty(totalQtyCU)
				.loadCuUOM(cuUOM)
				.huPackingMaterialsCollector(noopPackingMaterialsCollector)
				.build();

		data.helper.load(loadRequest);
		final List<I_M_HU> createdLUs = lutuProducer.getCreatedHUs();

		assertThat(createdLUs).hasSize(1);
		// data.helper.commitAndDumpHU(createdLUs.get(0));

		final I_M_HU createdLU = createdLUs.get(0);
		final IMutableHUContext huContext = data.helper.createMutableHUContextOutOfTransaction();
		huStatusBL.setHUStatus(huContext, createdLU, X_M_HU.HUSTATUS_Active);
		assertThat(createdLU.getHUStatus()).isEqualTo(X_M_HU.HUSTATUS_Active);
		createdLU.setM_Locator_ID(locator.getM_Locator_ID());

		M_HU.INSTANCE.updateChildren(createdLU);
		save(createdLU);

		final List<I_M_HU> createdAggregateHUs = handlingUnitsDAO.retrieveIncludedHUs(createdLUs.get(0));
		assertThat(createdAggregateHUs).hasSize(1);

		final I_M_HU cuToSplit = createdAggregateHUs.get(0);
		assertThat(handlingUnitsBL.isAggregateHU(cuToSplit)).isTrue();
		assertThat(cuToSplit.getM_HU_Item_Parent().getM_HU_PI_Item_ID()).isEqualTo(data.piLU_Item_IFCO.getM_HU_PI_Item_ID());
		assertThat(cuToSplit.getHUStatus()).isEqualTo(X_M_HU.HUSTATUS_Active);

		return createdLUs.get(0);
	}

	/**
	 * Makes a stand alone CU with the given quantity and status "active".
	 *
	 * @param strCuQty
	 * @return
	 */
	public I_M_HU mkRealStandAloneCUToSplit(final String strCuQty)
	{
		final HUProducerDestination producer = HUProducerDestination.ofVirtualPI();

		final TestHelperLoadRequest loadRequest = HUTestHelper.TestHelperLoadRequest.builder()
				.producer(producer)
				.cuProductId(data.helper.pTomatoProductId)
				.loadCuQty(new BigDecimal(strCuQty))
				.loadCuUOM(data.helper.uomKg)
				.huPackingMaterialsCollector(noopPackingMaterialsCollector)
				.build();

		data.helper.load(loadRequest);

		final List<I_M_HU> createdCUs = producer.getCreatedHUs();
		assertThat(createdCUs.size(), is(1));

		final I_M_HU cuToSplit = createdCUs.get(0);

		cuToSplit.setM_Locator_ID(locator.getM_Locator_ID());
		huStatusBL.setHUStatus(data.helper.getHUContext(), cuToSplit, X_M_HU.HUSTATUS_Active);
		save(cuToSplit);

		return cuToSplit;
	}

	public I_M_HU mkRealCUWithTUToSplit(final String strCuQty)
	{
		final LUTUProducerDestination lutuProducer = new LUTUProducerDestination();
		lutuProducer.setNoLU();
		lutuProducer.setTUPI(data.piTU_IFCO);

		final BigDecimal cuQty = new BigDecimal(strCuQty);
		data.helper.load(lutuProducer, data.helper.pTomatoProductId, cuQty, data.helper.uomKg);
		final List<I_M_HU> createdTUs = lutuProducer.getCreatedHUs();
		assertThat(createdTUs.size(), is(1));

		final I_M_HU createdTU = createdTUs.get(0);
		huStatusBL.setHUStatus(data.helper.getHUContext(), createdTU, X_M_HU.HUSTATUS_Active);
		createdTU.setM_Locator_ID(locator.getM_Locator_ID());

		M_HU.INSTANCE.updateChildren(createdTU);
		save(createdTU);

		final List<I_M_HU> createdCUs = handlingUnitsDAO.retrieveIncludedHUs(createdTU);
		assertThat(createdCUs.size(), is(1));

		final I_M_HU cuToSplit = createdCUs.get(0);
		cuToSplit.setM_Locator_ID(locator.getM_Locator_ID());

		return cuToSplit;
	}
}
