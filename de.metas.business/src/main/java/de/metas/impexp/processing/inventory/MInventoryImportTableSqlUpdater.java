package de.metas.impexp.processing.inventory;

import java.util.List;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.warehouse.LocatorId;
import org.adempiere.warehouse.WarehouseId;
import org.adempiere.warehouse.api.CreateOrUpdateLocatorRequest;
import org.adempiere.warehouse.api.IWarehouseDAO;
import org.compiere.model.I_I_Inventory;
import org.compiere.util.DB;
import org.compiere.util.TimeUtil;
import org.slf4j.Logger;

import de.metas.logging.LogManager;
import de.metas.organization.OrgId;
import de.metas.util.Services;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
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
 * A helper class for {@link InventoryImportProcess} that performs the "dirty" but efficient SQL updates on the {@link I_I_Inventory} table.
 * Those updates complements the data from existing metasfresh records and flag those import records that can't yet be imported.
 *
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@UtilityClass
final class MInventoryImportTableSqlUpdater
{
	private static final transient Logger logger = LogManager.getLogger(MInventoryImportTableSqlUpdater.class);

	public void updateInventoryImportTable(@NonNull final String whereClause)
	{
		dbUpdateLocatorDimensions(whereClause);
		dbUpdateWarehouse(whereClause);
		dbUpdateCreateLocators(whereClause);
		dbUpdateProducts(whereClause);
		dbUpdateSubProducer(whereClause);

		dbUpdateErrorMessages(whereClause);
	}

	private void dbUpdateLocatorDimensions(@NonNull final String whereClause)
	{
		final StringBuilder sql = new StringBuilder("UPDATE I_Inventory i "
				+ "SET WarehouseValue = COALESCE(i.WarehouseValue, dimensions.warehouseValue), "
				+ "    locatorvalue = COALESCE(i.locatorvalue, dimensions.locatorvalue), "
				+ "    X = COALESCE(i.X, dimensions.locatorX), "
				+ "    Y = COALESCE(i.Y, dimensions.locatorY), "
				+ "    Z = COALESCE(i.Z, dimensions.locatorZ), "
				+ "    X1 = COALESCE(i.X1, dimensions.locatorX1) "
				+ "FROM ("
				+ "      SELECT "
				+ "             d.warehouseValue, d.locatorValue, "
				+ "             d.locatorX, d.locatorY, d.locatorZ, d.locatorX1, "
				/* we have need an alias because the 'whereClause' param that metasfresh appends is not not qualified; */
				/* without the alias the SQL fails with "ERROR: column reference "i_inventory_id" is ambiguous" */
				+ "             inv.I_Inventory_ID as dimensions_I_Inventory_ID"
				+ "	     FROM I_Inventory as inv"
				+ "	       JOIN extractLocatorDimensions(inv.WarehouseLocatorIdentifier) as d on 1=1"
				+ "     ) AS dimensions "
				+ "WHERE I_IsImported<>'Y' AND dimensions.dimensions_I_Inventory_ID = i.I_Inventory_ID ")
						.append(whereClause);
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
	}

	private void dbUpdateWarehouse(@NonNull final String whereClause)
	{
		// Try to set M_Warehouse_ID based on warehouse value
		StringBuilder sql = new StringBuilder("UPDATE I_Inventory i ")
				.append("SET M_Warehouse_ID=(SELECT M_Warehouse_ID FROM M_Warehouse w WHERE i.WarehouseValue=w.Value) ")
				.append("WHERE M_Warehouse_ID IS NULL ")
				.append("AND I_IsImported<>'Y' ")
				.append(whereClause);
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);

		// Try to set M_Warehouse_ID based on locator value
		sql = new StringBuilder("UPDATE I_Inventory i ")
				.append("SET M_Warehouse_ID=(SELECT M_Warehouse_ID FROM M_Locator l WHERE i.locatorvalue=l.Value) ")
				.append("WHERE M_Warehouse_ID IS NULL ")
				.append("AND I_IsImported<>'Y' ")
				.append(whereClause);
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
	}

	private void dbUpdateCreateLocators(@NonNull final String whereClause)
	{
		dbUpdateLocators(whereClause);
		dbCreateLocators();
	}

	private void dbUpdateLocators(@NonNull final String whereClause)
	{
		// supplement missing M_Locator_ID from LocatorValue, M_Warehouse_ID and AD_Client_ID
		StringBuilder sql = new StringBuilder("UPDATE I_Inventory i ")
				.append("SET M_Locator_ID=(SELECT M_Locator_ID FROM M_Locator l ")
				.append("WHERE i.LocatorValue=l.Value AND i.M_Warehouse_ID = l.M_Warehouse_ID AND i.AD_Client_ID=l.AD_Client_ID) ")
				.append("WHERE M_Locator_ID IS NULL AND LocatorValue IS NOT NULL ")
				.append("AND I_IsImported<>'Y' ")
				.append(whereClause);
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);

		// supplement missing M_Locator_ID from standard warehouse locator
		sql = new StringBuilder("UPDATE I_Inventory i ")
				.append("SET M_Locator_ID=(SELECT M_Locator_ID FROM M_Locator l ")
				.append("WHERE l.IsDefault='Y' AND i.M_Warehouse_ID = l.M_Warehouse_ID AND i.AD_Client_ID=l.AD_Client_ID) ")
				.append("WHERE M_Locator_ID IS NULL AND LocatorValue IS NULL ")
				.append("AND I_IsImported<>'Y' ")
				.append(whereClause);
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);

		// update M_Locator.DateLastInventory
		sql = new StringBuilder("UPDATE M_Locator l ")
				.append("SET DateLastInventory=(SELECT DateLastInventory FROM I_Inventory i ")
				.append("WHERE i.LocatorValue=l.Value AND i.AD_Client_ID=l.AD_Client_ID ")
				.append("AND I_IsImported<>'Y' ")
				.append("ORDER BY i.DateLastInventory DESC LIMIT 1 ) ")
				.append("WHERE 1=1  ");
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);

		try
		{
			DB.commit(true, ITrx.TRXNAME_ThreadInherited);
		}
		catch (final Exception e)
		{
			throw new AdempiereException(e);
		}
	}

	private void dbCreateLocators()
	{
		final List<I_I_Inventory> unmatchedLocator = Services.get(IQueryBL.class).createQueryBuilder(I_I_Inventory.class)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_I_Inventory.COLUMNNAME_I_IsImported, false)
				.addNotNull(I_I_Inventory.COLUMN_LocatorValue)
				.addNotNull(I_I_Inventory.COLUMN_WarehouseValue)
				.addEqualsFilter(I_I_Inventory.COLUMNNAME_M_Locator_ID, null)
				.create()
				.list(I_I_Inventory.class);

		unmatchedLocator.forEach(importRecord -> {
			final LocatorId locatorId = getCreateNewMLocator(importRecord);
			if (locatorId != null)
			{
				importRecord.setM_Locator_ID(locatorId.getRepoId());
				InterfaceWrapperHelper.save(importRecord);
			}
		});
	}

	private LocatorId getCreateNewMLocator(@NonNull final I_I_Inventory importRecord)
	{
		//
		// check if exists, because might be created meanwhile
		final WarehouseId warehouseId = WarehouseId.ofRepoIdOrNull(importRecord.getM_Warehouse_ID());
		if (warehouseId == null)
		{
			return null;
		}

		final IWarehouseDAO warehousesRepo = Services.get(IWarehouseDAO.class);
		return warehousesRepo.createOrUpdateLocator(CreateOrUpdateLocatorRequest.builder()
				.warehouseId(warehouseId)
				.locatorValue(importRecord.getLocatorValue())
				.orgId(OrgId.ofRepoId(importRecord.getAD_Org_ID()))
				.x(importRecord.getX())
				.y(importRecord.getY())
				.z(importRecord.getZ())
				.x1(importRecord.getX1())
				.dateLastInventory(TimeUtil.asZonedDateTime(importRecord.getDateLastInventory()))
				.build());
	}

	private void dbUpdateProducts(@NonNull final String sqlImportTableWhereClause)
	{
		// Match by product value
		dbUpdateProducts(
				sqlImportTableWhereClause,
				"i.ProductValue LIKE 'val-%'",
				"p.Value = substr(i.ProductValue, 5)");

		// Match by M_Product_ID
		dbUpdateProducts(
				sqlImportTableWhereClause,
				"i.ProductValue ~ E'^\\\\d+$'",
				"p.M_Product_ID = i.ProductValue::numeric");

		// Match by UPC
		dbUpdateProducts(
				sqlImportTableWhereClause,
				"i.UPC IS NOT NULL",
				"p.UPC = i.UPC");

		// Fallback/backwards compatibility: Match by product value, without using the 'val-' prefix
		dbUpdateProducts(
				sqlImportTableWhereClause,
				"i.ProductValue IS NOT NULL",
				"p.Value = i.ProductValue");
	}

	private static int dbUpdateProducts(
			@NonNull final String importTableWhereClause,
			@NonNull final String importValueFormatMatcher,
			@NonNull final String importValueMatchCondition)
	{
		final String sqlProductId = "SELECT MAX(M_Product_ID)"
				+ " FROM M_Product p"
				+ " WHERE"
				+ " i.AD_Client_ID=p.AD_Client_ID"
				+ " AND (" + importValueMatchCondition + ")";

		final String sql = "UPDATE I_Inventory i "
				+ " SET M_Product_ID=(" + sqlProductId + ")"
				+ " WHERE"
				+ " I_IsImported<>'Y'"
				+ " AND M_Product_ID IS NULL"
				+ " AND i.ProductValue IS NOT NULL"
				+ " AND (" + importValueFormatMatcher + ")"
				+ " " + importTableWhereClause;

		return DB.executeUpdateEx(sql, ITrx.TRXNAME_ThreadInherited);
	}

	private void dbUpdateSubProducer(@NonNull final String whereClause)
	{
		// Set M_Warehouse_ID
		final StringBuilder sql = new StringBuilder("UPDATE I_Inventory i ")
				.append("SET SubProducer_BPartner_ID=(SELECT C_BPartner_ID FROM C_BPartner bp WHERE i.SubProducerBPartner_Value=bp.value) ")
				.append("WHERE SubProducer_BPartner_ID IS NULL ")
				.append("AND I_IsImported<>'Y' ")
				.append(whereClause);
		DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
	}

	public int countRecordsWithErrors(@NonNull final String whereClause)
	{
		final String sql = "SELECT COUNT(1) FROM I_Inventory "
				+ " WHERE I_IsImported='E'"
				+ " " + whereClause;
		return DB.getSQLValueEx(ITrx.TRXNAME_ThreadInherited, sql);
	}

	private void dbUpdateErrorMessages(@NonNull final String whereClause)
	{
		//
		// No Organization
		{
			final String sql = "UPDATE I_Inventory "
					+ " SET I_IsImported='E', I_ErrorMsg=I_ErrorMsg||'ERR=No Organization, ' "
					+ " WHERE (AD_Org_ID IS NULL OR AD_Org_ID=" + OrgId.ANY.getRepoId() + ")"
					+ " AND I_IsImported<>'Y' "
					+ " " + whereClause;
			final int no = DB.executeUpdateEx(sql, ITrx.TRXNAME_ThreadInherited);
			if (no != 0)
			{
				logger.warn("No Organization = {}", no);
			}
		}

		//
		// No Locator
		{
			final StringBuilder sql = new StringBuilder("UPDATE I_Inventory ")
					.append(" SET I_IsImported='E', I_ErrorMsg=I_ErrorMsg||'ERR=No Locator, ' ")
					.append(" WHERE M_Locator_ID IS NULL ")
					.append(" AND I_IsImported<>'Y' ")
					.append(whereClause);
			final int no = DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
			if (no != 0)
			{
				logger.warn("No Locator = {}", no);
			}
		}

		//
		// No Warehouse
		{
			final StringBuilder sql = new StringBuilder("UPDATE I_Inventory ")
					.append(" SET I_IsImported='E', I_ErrorMsg=I_ErrorMsg||'ERR=No Warehouse, ' ")
					.append(" WHERE M_Warehouse_ID IS NULL ")
					.append(" AND I_IsImported<>'Y' ")
					.append(whereClause);
			final int no = DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
			if (no != 0)
			{
				logger.warn("No Warehouse = {}", no);
			}
		}

		//
		// No Product
		{
			final StringBuilder sql = new StringBuilder("UPDATE I_Inventory ")
					.append(" SET I_IsImported='E', I_ErrorMsg=I_ErrorMsg||'ERR=No Product, ' ")
					.append(" WHERE M_Product_ID IS NULL ")
					.append(" AND I_IsImported<>'Y' ")
					.append(whereClause);
			final int no = DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
			if (no != 0)
			{
				logger.warn("No Product = {}", no);
			}
		}

		// No QtyCount
		{
			final StringBuilder sql = new StringBuilder("UPDATE I_Inventory ")
					.append(" SET I_IsImported='E', I_ErrorMsg=I_ErrorMsg||'ERR=No qtycount, ' ")
					.append(" WHERE qtycount IS NULL ")
					.append(" AND I_IsImported<>'Y' ")
					.append(whereClause);
			final int no = DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
			if (no != 0)
			{
				logger.warn("No qtycount = {}", no);
			}
		}

	}
}
