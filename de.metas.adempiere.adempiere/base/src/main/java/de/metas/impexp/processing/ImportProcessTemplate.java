package de.metas.impexp.processing;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.ad.trx.processor.api.FailTrxItemExceptionHandler;
import org.adempiere.ad.trx.processor.api.ITrxItemExecutorBuilder.OnItemErrorPolicy;
import org.adempiere.ad.trx.processor.api.ITrxItemProcessorExecutorService;
import org.adempiere.ad.trx.processor.spi.TrxItemChunkProcessorAdapter;
import org.adempiere.db.util.AbstractPreparedStatementBlindIterator;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.service.ClientId;
import org.adempiere.util.api.IParams;
import org.adempiere.util.lang.IMutable;
import org.adempiere.util.lang.Mutable;
import org.adempiere.util.lang.impl.TableRecordReferenceSet;
import org.compiere.SpringContextHolder;
import org.compiere.model.I_C_DataImport;
import org.compiere.model.ModelValidationEngine;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.ISqlUpdateReturnProcessor;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableMap;

import ch.qos.logback.classic.Level;
import de.metas.cache.CacheMgt;
import de.metas.cache.model.CacheInvalidateMultiRequest;
import de.metas.error.AdIssueId;
import de.metas.error.IErrorManager;
import de.metas.impexp.DataImportConfigId;
import de.metas.impexp.ImportTableDescriptor;
import de.metas.impexp.ImportTableDescriptorRepository;
import de.metas.impexp.processing.ImportProcessResult.ImportProcessResultCollector;
import de.metas.logging.LogManager;
import de.metas.process.PInstanceId;
import de.metas.util.Check;
import de.metas.util.ILoggable;
import de.metas.util.Loggables;
import de.metas.util.Services;
import de.metas.util.collections.IteratorUtils;
import lombok.NonNull;

/**
 * Base implementation of {@link IImportProcess}.
 *
 * Implementors shall extend this class instead of implementing {@link IImportProcess}.
 *
 * @author tsa
 *
 * @param <ImportRecordType> import table model (e.g. I_I_BPartner).
 */
public abstract class ImportProcessTemplate<ImportRecordType> implements IImportProcess<ImportRecordType>
{
	public static final String COLUMNNAME_I_IsImported = "I_IsImported";
	public static final String COLUMNNAME_I_ErrorMsg = "I_ErrorMsg";
	public static final String COLUMNNAME_Processed = "Processed";
	public static final String COLUMNNAME_Processing = "Processing";
	public static final String COLUMNNAME_C_DataImport_ID = I_C_DataImport.COLUMNNAME_C_DataImport_ID;

	// services
	protected final transient Logger log = LogManager.getLogger(getClass());
	private final ITrxManager trxManager = Services.get(ITrxManager.class);
	private final IErrorManager errorManager = Services.get(IErrorManager.class);
	private final DBFunctionsRepository dbFunctionsRepo = SpringContextHolder.instance.getBean(DBFunctionsRepository.class);
	private final ImportTableDescriptorRepository importTableDescriptorRepo = SpringContextHolder.instance.getBean(ImportTableDescriptorRepository.class);

	//
	// Parameters
	private Properties _ctx;
	private ClientId clientId;
	private Boolean validateOnly;
	private boolean completeDocuments;
	private IParams _parameters = IParams.NULL;
	private ILoggable loggable = Loggables.getLoggableOrLogger(log, Level.INFO);
	private TableRecordReferenceSet selectedRecordRefs;

	private ImportProcessResultCollector resultCollector;

	private ImportTableDescriptor _importTableDescriptor; // lazy
	private DBFunctions dbFunctions; // lazy
	private PInstanceId selectionId; // lazy
	private String whereClause; // lazy

	private void assertNotStarted()
	{
		if (resultCollector != null)
		{
			throw new AdempiereException("Cannot change parameters after process is started: " + this);
		}
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> setCtx(final Properties ctx)
	{
		assertNotStarted();

		this._ctx = ctx;
		return this;
	}

	public final Properties getCtx()
	{
		Check.assumeNotNull(_ctx, "_ctx not null");
		return _ctx;
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> clientId(@NonNull final ClientId clientId)
	{
		assertNotStarted();

		this.clientId = clientId;
		return this;
	}

	private final ClientId getClientId()
	{
		if (clientId != null)
		{
			return clientId;
		}

		return Env.getClientId(getCtx());
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> setParameters(@NonNull final IParams params)
	{
		assertNotStarted();

		this._parameters = params;
		return this;
	}

	protected final IParams getParameters()
	{
		return _parameters;
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> validateOnly(final boolean validateOnly)
	{
		assertNotStarted();

		this.validateOnly = validateOnly;
		return this;
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> completeDocuments(final boolean completeDocuments)
	{
		assertNotStarted();

		this.completeDocuments = completeDocuments;
		return this;
	}

	private DBFunctions getDbFunctions()
	{
		DBFunctions dbFunctions = this.dbFunctions;
		if (dbFunctions == null)
		{
			dbFunctions = this.dbFunctions = dbFunctionsRepo.retrieveByTableName(getImportTableName());
		}
		return dbFunctions;
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> setLoggable(@NonNull final ILoggable loggable)
	{
		assertNotStarted();

		this.loggable = loggable;
		return this;
	}

	protected final ILoggable getLoggable()
	{
		return loggable;
	}

	private final boolean isValidateOnly()
	{
		final Boolean validateOnly = this.validateOnly;
		if (validateOnly != null)
		{
			return validateOnly;
		}

		return getParameters().getParameterAsBool(PARAM_IsValidateOnly);
	}

	protected final boolean isCompleteDocuments()
	{
		if (this.completeDocuments)
		{
			return true;
		}
		return getParameters().getParameterAsBool(PARAM_IsDocComplete);
	}

	@Override
	public final ImportProcessTemplate<ImportRecordType> selectedRecords(@NonNull final TableRecordReferenceSet selectedRecordRefs)
	{
		assertNotStarted();

		if (selectedRecordRefs.isEmpty())
		{
			throw new AdempiereException("No import records: " + selectedRecordRefs);
		}
		this.selectedRecordRefs = selectedRecordRefs;
		return this;
	}

	private final PInstanceId getOrCreateSelectionId()
	{
		if (selectionId != null)
		{
			return selectionId;
		}

		if (selectedRecordRefs != null)
		{
			selectionId = DB.createT_Selection(selectedRecordRefs, ITrx.TRXNAME_None);
			return selectionId;
		}

		return PInstanceId.ofRepoIdOrNull(getParameters().getParameterAsInt(PARAM_Selection_ID, -1));
	}

	protected final boolean isInsertOnly()
	{
		return getParameters().getParameterAsBool(PARAM_IsInsertOnly);
	}

	private final boolean isDeleteOldImported()
	{
		return getParameters().getParameterAsBool(PARAM_DeleteOldImported);
	}

	private ImportTableDescriptor getImportTableDescriptor()
	{
		ImportTableDescriptor importTableDescriptor = this._importTableDescriptor;
		if (importTableDescriptor == null)
		{
			importTableDescriptor = this._importTableDescriptor = importTableDescriptorRepo.getByTableName(getImportTableName());
		}
		return importTableDescriptor;
	}

	protected final String getImportKeyColumnName()
	{
		return getImportTableDescriptor().getKeyColumnName();
	}

	protected abstract String getTargetTableName();

	/** @return SQL WHERE clause to filter records that are candidates for import; <b>please prefix your where clause with " AND "</b> */
	protected final String getWhereClause()
	{
		String whereClause = this.whereClause;
		if (whereClause == null)
		{
			whereClause = this.whereClause = buildWhereClause();
			log.debug("Using where clause: {}", whereClause);
		}
		return whereClause;
	}

	private final String buildWhereClause()
	{
		final StringBuilder whereClause = new StringBuilder();

		// AD_Client
		final ClientId clientId = getClientId();
		whereClause.append(" AND AD_Client_ID=").append(clientId.getRepoId());

		// Selection_ID
		final PInstanceId selectionId = getOrCreateSelectionId();
		if (selectionId != null)
		{
			whereClause.append(" AND ").append(DB.createT_Selection_SqlWhereClause(selectionId, getImportKeyColumnName()));
		}

		return whereClause.toString();
	}

	protected final ImportProcessResultCollector getResultCollector()
	{
		if (resultCollector == null)
		{
			throw new AdempiereException("Import not started yet");
		}
		return resultCollector;
	}

	@Override
	public final ImportProcessResult run()
	{
		if (resultCollector != null)
		{
			throw new AdempiereException("Process already started: " + this);
		}
		resultCollector = ImportProcessResult.newCollector(getTargetTableName())
				.importTableName(getImportTableName());

		// Assume we are not running in another transaction because that could introduce deadlocks,
		// because we are creating the transactions here.
		trxManager.assertThreadInheritedTrxNotExists();

		//
		// Delete old imported records (out of trx)
		if (isDeleteOldImported())
		{
			final int countImportRecordsDeleted = deleteImportRecords(ImportDataDeleteRequest.builder()
					.mode(ImportDataDeleteMode.ONLY_IMPORTED)
					.build());
			resultCollector.setCountImportRecordsDeleted(countImportRecordsDeleted);
			loggable.addLog("Deleted Old Imported =" + countImportRecordsDeleted);
		}

		//
		// Reset standard columns (out of trx)
		resetStandardColumns();

		//
		// Update and validate
		ModelValidationEngine.get().fireImportValidate(this, null, null, IImportInterceptor.TIMING_BEFORE_VALIDATE);
		trxManager.runInNewTrx(() -> updateAndValidateImportRecords());
		ModelValidationEngine.get().fireImportValidate(this, null, null, IImportInterceptor.TIMING_AFTER_VALIDATE);
		if (isValidateOnly())
		{
			return resultCollector.toResult();
		}

		//
		// Actual import (allow the method to manage the transaction)
		importData();

		final ImportProcessResult result = resultCollector.toResult();
		loggable.addLog("" + resultCollector);
		return result;
	}

	@Override
	public final int deleteImportRecords(@NonNull final ImportDataDeleteRequest request)
	{
		final StringBuilder sql = new StringBuilder("DELETE FROM " + getImportTableName() + " WHERE 1=1");

		//
		sql.append("\n /* standard import filter */ ").append(getWhereClause());

		//
		// Delete mode filters
		final boolean appendViewSqlWhereClause;
		final ImportDataDeleteMode mode = request.getMode();
		if (ImportDataDeleteMode.ONLY_SELECTED.equals(mode))
		{
			appendViewSqlWhereClause = false;
			if (!Check.isEmpty(request.getSelectionSqlWhereClause(), true))
			{
				sql.append("\n /* selection */ AND ").append(request.getSelectionSqlWhereClause());
			}
		}
		else if (ImportDataDeleteMode.ALL.equals(mode))
		{
			// allow to delete ALL for current selection
			appendViewSqlWhereClause = true;
		}
		else if (ImportDataDeleteMode.ONLY_IMPORTED.equals(mode))
		{
			appendViewSqlWhereClause = true;
			sql.append("\n /* only imported */ AND ").append(COLUMNNAME_I_IsImported).append("='Y'");
		}
		else
		{
			throw new AdempiereException("Unknown mode: " + mode);
		}

		//
		// View filter
		if (appendViewSqlWhereClause
				&& !Check.isEmpty(request.getViewSqlWhereClause(), true))
		{
			sql.append("\n /* view */ AND (").append(request.getViewSqlWhereClause()).append(")");
		}

		//
		// Delete
		final int deletedCount = DB.executeUpdateEx(sql.toString(), ITrx.TRXNAME_ThreadInherited);
		return deletedCount;
	}

	/** @return a map of ImportTable_ColumnName to DefaultValue, to be used when the value is null */
	protected Map<String, Object> getImportTableDefaultValues()
	{
		return ImmutableMap.of();
	}

	/**
	 * Reset standard columns (Client, Org, IsActive, Created/Updated).
	 *
	 * Called before starting to validate.
	 */
	protected void resetStandardColumns()
	{
		final StringBuilder sql = new StringBuilder("UPDATE " + getImportTableName()
				+ " SET AD_Client_ID = COALESCE (AD_Client_ID, ").append(getClientId().getRepoId()).append("),"
						+ " AD_Org_ID = COALESCE (AD_Org_ID, 0),"
						+ " IsActive = COALESCE (IsActive, 'Y'),"
						+ " Created = COALESCE (Created, now()),"
						+ " CreatedBy = COALESCE (CreatedBy, 0),"
						+ " Updated = COALESCE (Updated, now()),"
						+ " UpdatedBy = COALESCE (UpdatedBy, 0),"
						+ COLUMNNAME_I_ErrorMsg + " = ' ',"
						+ COLUMNNAME_I_IsImported + "= 'N' ");
		final List<Object> sqlParams = new ArrayList<>();

		for (final Map.Entry<String, Object> defaultValueEntry : getImportTableDefaultValues().entrySet())
		{
			final String columnName = defaultValueEntry.getKey();
			final Object value = defaultValueEntry.getValue();

			sql.append("\n, " + columnName + "=COALESCE(" + columnName + ", ?)");
			sqlParams.add(value);
		}

		sql.append("\n WHERE (" + COLUMNNAME_I_IsImported + "<>'Y' OR " + COLUMNNAME_I_IsImported + " IS NULL) " + getWhereClause());
		final int no = DB.executeUpdateEx(sql.toString(),
				sqlParams.toArray(),
				ITrx.TRXNAME_ThreadInherited);
		log.debug("Reset={}", no);

	}

	/**
	 * Prepare data import: fill missing fields (if possible) and validate the records.
	 */
	protected abstract void updateAndValidateImportRecords();

	protected abstract ImportGroupKey extractImportGroupKey(final ImportRecordType importRecord);

	/**
	 * Actual data import.
	 */
	private final void importData()
	{
		final ITrxItemProcessorExecutorService trxItemProcessorExecutorService = Services.get(ITrxItemProcessorExecutorService.class);

		final IMutable<Object> stateHolder = new Mutable<>();
		final Mutable<ImportGroup<ImportRecordType>> currentImportGroupHolder = new Mutable<>();

		trxItemProcessorExecutorService
				.<ImportRecordType, Void> createExecutor()
				.setOnItemErrorPolicy(OnItemErrorPolicy.CancelChunkAndRollBack)
				.setExceptionHandler(new FailTrxItemExceptionHandler()
				{
					@Override
					public void onCompleteChunkError(final Throwable ex)
					{
						// do nothing.
						// the error will be handled in "afterCompleteChunkError" method
					}

					@Override
					public void afterCompleteChunkError(final Throwable ex)
					{
						final ImportGroup<ImportRecordType> currentGroup = currentImportGroupHolder.getValue();
						markAsError(currentGroup, ex);
					}
				})
				.setProcessor(new TrxItemChunkProcessorAdapter<ImportRecordType, Void>()
				{
					@Override
					public void newChunk(final ImportRecordType importRecord)
					{
						final ImportGroupKey groupKey = extractImportGroupKey(importRecord);
						currentImportGroupHolder.setValue(ImportGroup.newInstance(groupKey));
					}

					@Override
					public boolean isSameChunk(final ImportRecordType importRecord)
					{
						final ImportGroup<ImportRecordType> currentGroup = currentImportGroupHolder.getValue();
						final ImportGroupKey groupKey = extractImportGroupKey(importRecord);
						return Objects.equals(currentGroup.getGroupKey(), groupKey);
					}

					@Override
					public void process(final ImportRecordType importRecord)
					{
						final ImportGroup<ImportRecordType> currentGroup = currentImportGroupHolder.getValue();
						currentGroup.addImportRecord(importRecord);
					}

					@Override
					public void completeChunk()
					{
						final ImportGroup<ImportRecordType> currentGroup = currentImportGroupHolder.getValue();
						importGroup(currentGroup, stateHolder);
					}

					@Override
					public void cancelChunk()
					{
						// nothing
					}
				})
				//
				.process(retrieveRecordsToImport());
	}

	private Iterator<ImportRecordType> retrieveRecordsToImport()
	{
		final Properties ctx = Env.getCtx();
		final String sql = buildSqlSelectRecordsToImport();

		return IteratorUtils.asIterator(new AbstractPreparedStatementBlindIterator<ImportRecordType>()
		{

			@Override
			protected PreparedStatement createPreparedStatement() throws SQLException
			{
				return DB.prepareStatement(sql, ITrx.TRXNAME_ThreadInherited);
			}

			@Override
			protected ImportRecordType fetch(final ResultSet rs) throws SQLException
			{
				return retrieveImportRecord(ctx, rs);
			}
		});
	}

	private void importGroup(
			@NonNull final ImportGroup<ImportRecordType> importGroup,
			@NonNull final IMutable<Object> stateHolder)
	{
		// shall not happen
		if (importGroup.isEmpty())
		{
			log.warn("Skip importing empty group: {}", importGroup);
			return;
		}

		try
		{
			final ImportGroupResult importGroupResult = importRecords(importGroup.getImportRecords(), stateHolder);

			for (final ImportRecordType importRecord : importGroup.getImportRecords())
			{
				markImported(importRecord);
				runSQLAfterRowImport(importRecord); // run after markImported because we need the recordId saved
			}

			final ImportProcessResultCollector overallResultCollector = getResultCollector();
			overallResultCollector.addInsertsIntoTargetTable(importGroupResult.getCountInserted());
			overallResultCollector.addUpdatesIntoTargetTable(importGroupResult.getCountUpdated());
		}
		catch (Exception ex)
		{
			throw AdempiereException.wrapIfNeeded(ex);
		}
	}

	private String buildSqlSelectRecordsToImport()
	{
		final String whereClause = getWhereClause();
		final StringBuilder sql = new StringBuilder("SELECT * FROM " + getImportTableName() + " WHERE " + COLUMNNAME_I_IsImported + "='N' ").append(whereClause);

		// ORDER BY
		sql.append(" ORDER BY ");
		final String sqlOrderBy = getImportOrderBySql();
		if (!Check.isEmpty(sqlOrderBy, true))
		{
			sql.append(sqlOrderBy);
		}
		else
		{
			sql.append(getImportKeyColumnName());
		}

		return sql.toString();
	}

	protected abstract String getImportOrderBySql();

	protected abstract ImportRecordType retrieveImportRecord(final Properties ctx, final ResultSet rs) throws SQLException;

	protected abstract ImportGroupResult importRecords(
			final List<ImportRecordType> importRecords,
			final IMutable<Object> stateHolder) throws Exception;

	private final void markAsError(
			@NonNull final ImportGroup<ImportRecordType> importGroup,
			@NonNull final Throwable exception)
	{
		final ImportTableDescriptor importTableDescriptor = getImportTableDescriptor();
		final String importTableName = importTableDescriptor.getTableName();
		final String keyColumnName = importTableDescriptor.getKeyColumnName();
		final Set<Integer> importRecordIds = importGroup.getImportRecordIds();

		final ArrayList<Object> sqlParams = new ArrayList<>();
		final StringBuilder sql = new StringBuilder("UPDATE " + importTableName + " SET ");

		// I_IsImported
		sql.append(COLUMNNAME_I_IsImported + "=?");
		sqlParams.add("E");

		// I_ErrorMsg
		{
			final String errorMsg = AdempiereException.extractMessage(exception);

			sql.append(", " + COLUMNNAME_I_ErrorMsg + "=I_ErrorMsg || ?");
			sqlParams.add(Check.isEmpty(errorMsg, true) ? "" : errorMsg + ", ");
		}

		// AD_Issue_ID
		if (importTableDescriptor.getAdIssueIdColumnName() != null)
		{
			final AdIssueId adIssueId = errorManager.createIssue(exception);

			sql.append(", " + importTableDescriptor.getAdIssueIdColumnName() + "=?");
			sqlParams.add(adIssueId);
		}

		//
		// WHERE clause
		sql.append(" WHERE " + DB.buildSqlList(keyColumnName, importRecordIds));

		//
		// Execute
		DB.executeUpdateEx(
				sql.toString(),
				sqlParams.toArray(),
				ITrx.TRXNAME_ThreadInherited,
				0, // no timeOut
				(ISqlUpdateReturnProcessor)null);

		// just in case some BL wants to get values from it
		importGroup.getImportRecords().forEach(InterfaceWrapperHelper::markStaled);

		CacheMgt.get().resetLocalNowAndBroadcastOnTrxCommit(
				ITrx.TRXNAME_ThreadInherited,
				CacheInvalidateMultiRequest.fromTableNameAndRecordIds(importTableName, importRecordIds));
	}

	protected final int markNotImportedAllWithErrors()
	{
		final String sql = "UPDATE " + getImportTableName()
				+ " SET " + COLUMNNAME_I_IsImported + "='N', Updated=now() "
				+ " WHERE " + COLUMNNAME_I_IsImported + "<>'Y' "
				+ " " + getWhereClause();
		final int countNotImported = DB.executeUpdateEx(sql, ITrx.TRXNAME_ThreadInherited);
		return countNotImported >= 0 ? countNotImported : 0;
	}

	protected void markImported(final ImportRecordType importRecord)
	{
		InterfaceWrapperHelper.setValue(importRecord, COLUMNNAME_I_IsImported, true);
		InterfaceWrapperHelper.setValue(importRecord, COLUMNNAME_Processed, true);
		InterfaceWrapperHelper.setValue(importRecord, COLUMNNAME_Processing, false);
		InterfaceWrapperHelper.save(importRecord);
	}

	protected void afterImport(final IMutable<Object> state)
	{
		// nothing to do here
	}

	private final void runSQLAfterRowImport(@NonNull final ImportRecordType importRecord)
	{
		final List<DBFunction> functions = getDbFunctions().getAvailableAfterRowFunctions();
		if (functions.isEmpty())
		{
			return;
		}

		final DataImportConfigId dataImportConfigId = extractDataImportConfigIdOrNull(importRecord);
		final Optional<Integer> recordId = InterfaceWrapperHelper.getValue(importRecord, getImportKeyColumnName());
		functions.forEach(function -> DBFunctionHelper.doDBFunctionCall(function, dataImportConfigId, recordId.orElse(0)));
	}

	private DataImportConfigId extractDataImportConfigIdOrNull(@NonNull final ImportRecordType importRecord)
	{
		final Optional<Integer> value = InterfaceWrapperHelper.getValue(importRecord, COLUMNNAME_C_DataImport_ID);
		return value.map(DataImportConfigId::ofRepoIdOrNull).orElse(null);
	}
}
