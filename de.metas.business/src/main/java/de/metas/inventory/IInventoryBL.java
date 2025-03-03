package de.metas.inventory;

import java.util.List;

import org.compiere.model.I_M_Inventory;
import org.compiere.model.I_M_InventoryLine;

import de.metas.document.engine.DocStatus;
import de.metas.quantity.Quantity;
import de.metas.util.ISingletonService;

/**
 * @author ad
 *
 */
public interface IInventoryBL extends ISingletonService
{
	/**
	 * Gets the charge we use for internal inventory charge (from a sysconfig).
	 * Used in quick input and automatically generated inventory lines.
	 * Never returns non-positive.
	 */
	int getDefaultInternalChargeId();

	void addDescription(I_M_Inventory inventory, String descriptionToAdd);

	void addDescription(I_M_InventoryLine inventoryLine, String descriptionToAdd);

	DocStatus getDocStatus(InventoryId inventoryId);

	boolean isComplete(I_M_Inventory inventory);

	boolean isSOTrx(I_M_InventoryLine inventoryLine);

	boolean isInternalUseInventory(I_M_InventoryLine inventoryLine);

	/**
	 * <li>negative value means outgoing trx
	 * <li>positive value means incoming trx
	 */
	Quantity getMovementQty(I_M_InventoryLine inventoryLine);

	Quantity getMovementQtyInStockingUOM(I_M_InventoryLine inventoryLine);

	void assignToInventoryCounters(List<I_M_InventoryLine> inventoryLines, int numberOfCounters);
}
