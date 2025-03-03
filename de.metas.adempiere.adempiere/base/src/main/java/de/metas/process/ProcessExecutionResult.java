package de.metas.process;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import org.compiere.util.Util;

import javax.annotation.concurrent.Immutable;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.print.MPrintFormat;
import org.compiere.util.DisplayType;
import org.compiere.util.Env;
import org.compiere.util.MimeType;
import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import de.metas.i18n.IMsgBL;
import de.metas.logging.LogManager;
import de.metas.process.ProcessExecutionResult.RecordsToOpen.OpenTarget;
import de.metas.util.Check;
import de.metas.util.Services;
import de.metas.util.lang.RepoIdAware;
import de.metas.util.time.SystemTime;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2016 metas GmbH
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

@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public class ProcessExecutionResult
{
	public static ProcessExecutionResult newInstanceForADPInstanceId(final PInstanceId pinstanceId)
	{
		return new ProcessExecutionResult(pinstanceId);
	}

	/**
	 * Display process logs to user policy
	 */
	public static enum ShowProcessLogs
	{
		/** Always display them */
		Always,
		/** Display them only if the process failed */
		OnError,
		/** Never display them */
		Never
	};

	private static final transient Logger logger = LogManager.getLogger(ProcessExecutionResult.class);

	private PInstanceId pinstanceId;

	/** Summary of Execution */
	private String summary = "";
	/** Execution had an error */
	private boolean error = false;
	private transient boolean errorWasReportedToUser = false;

	/** Process timed out */
	private boolean timeout = false;

	/** Log Info */
	private transient List<ProcessInfoLog> logs;
	private ShowProcessLogs showProcessLogsPolicy = ShowProcessLogs.Always;

	//
	// Reporting
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private transient MPrintFormat printFormat;
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private byte[] reportData;
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private String reportFilename;
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private String reportContentType;

	/**
	 * If the process fails with an Throwable, the Throwable is caught and stored here
	 */
	// 03152: motivation to add this is that now in ait we can assert that a certain exception was thrown.
	private transient Throwable throwable = null;

	private boolean refreshAllAfterExecution = false;

	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private TableRecordReference recordToRefreshAfterExecution = null;

	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private TableRecordReference recordToSelectAfterExecution = null;

	/** Records to be opened (UI) after this process was successfully executed */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private RecordsToOpen recordsToOpen = null;

	@Getter
	@Setter
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private WebuiViewToOpen webuiViewToOpen = null;

	@Getter
	@Setter
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private DisplayQRCode displayQRCode;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String webuiViewId = null;

	private ProcessExecutionResult(final PInstanceId pinstanceId)
	{
		this.pinstanceId = pinstanceId;
		this.logs = new ArrayList<>();
	}

	// note: my local ProcessExecutionResultTest failed without this constructor
	@JsonCreator
	public ProcessExecutionResult(
			@JsonProperty("pinstanceId") final PInstanceId pinstanceId, 
			@JsonProperty("summary") final String summary, 
			@JsonProperty("error") final boolean error, 
			//@JsonProperty("errorWasReportedToUser") final boolean errorWasReportedToUser, // transient
			@JsonProperty("timeout") final boolean timeout, 
			//@JsonProperty("logs") final List<ProcessInfoLog> logs, // transient
			@JsonProperty("showProcessLogsPolicy") final ShowProcessLogs showProcessLogsPolicy, 
			//@JsonProperty("printFormat") final MPrintFormat printFormat, // transient
			@JsonProperty("reportData") final byte[] reportData, 
			@JsonProperty("reportFilename") final String reportFilename, 
			@JsonProperty("reportContentType") final String reportContentType, 
			//@JsonProperty("throwable") final Throwable throwable, // transient
			@JsonProperty("refreshAllAfterExecution") final boolean refreshAllAfterExecution,
			@JsonProperty("recordToRefreshAfterExecution") final TableRecordReference recordToRefreshAfterExecution, 
			@JsonProperty("recordToSelectAfterExecution") final TableRecordReference recordToSelectAfterExecution, 
			@JsonProperty("recordsToOpen") final RecordsToOpen recordsToOpen,
			@JsonProperty("webuiViewToOpen") final WebuiViewToOpen webuiViewToOpen, 
			@JsonProperty("displayQRCode") final DisplayQRCode displayQRCode, 
			@JsonProperty("webuiViewId") final String webuiViewId)
	{
		this.pinstanceId = pinstanceId;
		this.summary = summary;
		this.error = error;
		this.timeout = timeout;
		this.showProcessLogsPolicy = showProcessLogsPolicy;
		this.reportData = reportData;
		this.reportFilename = reportFilename;
		this.reportContentType = reportContentType;
		this.refreshAllAfterExecution = refreshAllAfterExecution;
		this.recordToRefreshAfterExecution = recordToRefreshAfterExecution;
		this.recordToSelectAfterExecution = recordToSelectAfterExecution;
		this.recordsToOpen = recordsToOpen;
		this.webuiViewToOpen = webuiViewToOpen;
		this.displayQRCode = displayQRCode;
		this.webuiViewId = webuiViewId;
	}
	
	@Override
	public String toString()
	{
		return MoreObjects.toStringHelper(this)
				.omitNullValues()
				.add("summary", summary)
				.add("error", error)
				.add("printFormat", printFormat)
				.add("logs.size", logs == null ? 0 : logs.size())
				.add("AD_PInstance_ID", pinstanceId)
				.add("recordToSelectAfterExecution", recordToSelectAfterExecution)
				.add("recordsToOpen", recordsToOpen)
				.add("viewToOpen", webuiViewToOpen)
				.toString();
	}

	/* package */void setPInstanceId(final PInstanceId pinstanceId)
	{
		this.pinstanceId = pinstanceId;
	}

	public PInstanceId getPinstanceId()
	{
		return pinstanceId;
	}

	public String getSummary()
	{
		return summary;
	}

	public void setSummary(final String summary)
	{
		this.summary = summary;
	}

	public void addSummary(final String additionalSummary)
	{
		if (summary == null)
		{
			summary = additionalSummary;
		}
		else
		{
			summary += additionalSummary;
		}

	}

	public void markAsSuccess(final String summary)
	{
		this.summary = summary;
		throwable = null;
		error = false;
	}

	public void markAsError(final Throwable throwable)
	{
		Check.assumeNotNull(throwable, "Parameter throwable is not null");
		final String summary = throwable.getLocalizedMessage();
		markAsError(summary, throwable);
	}

	public void markAsError(final String errorMsg)
	{
		final Throwable throwable = null;
		markAsError(errorMsg, throwable);
	}

	public void markAsError(final String summary, final Throwable throwable)
	{
		this.summary = summary;
		this.throwable = throwable;
		error = true;
	}

	/**
	 * @return true if the process execution failed
	 */
	public boolean isError()
	{
		return error;
	}

	public void setThrowableIfNotSet(final Throwable throwable)
	{
		// Don't set it if it was already set
		if (this.throwable != null)
		{
			return;
		}

		this.throwable = throwable;
	}

	/**
	 * If the process has failed with a Throwable, that Throwable can be retrieved using this getter.
	 *
	 * @return throwable
	 * @task 03152
	 */
	public Throwable getThrowable()
	{
		return throwable;
	}

	public void setErrorWasReportedToUser()
	{
		errorWasReportedToUser = true;
	}

	public boolean isErrorWasReportedToUser()
	{
		return errorWasReportedToUser;
	}

	public void setTimeout(final boolean timeout)
	{
		this.timeout = timeout;
	}

	public boolean isTimeout()
	{
		return timeout;
	}

	/**
	 * Sets if the process logs (if any) shall be displayed to user
	 *
	 * @param showProcessLogsPolicy
	 */
	public final void setShowProcessLogs(final ShowProcessLogs showProcessLogsPolicy)
	{
		Check.assumeNotNull(showProcessLogsPolicy, "showProcessLogsPolicy not null");
		this.showProcessLogsPolicy = showProcessLogsPolicy;
	}

	/**
	 * @return true if the process logs (if any) shall be displayed to user
	 */
	@JsonIgnore
	public final boolean isShowProcessLogs()
	{
		switch (showProcessLogsPolicy)
		{
			case Always:
				return true;
			case Never:
				return false;
			case OnError:
				return isError();
			default:
				logger.warn("Unknown ShowProcessLogsPolicy: {}. Considering {}", showProcessLogsPolicy, ShowProcessLogs.Always);
				return true;
		}
	}

	/** Sets if the whole window tab shall be refreshed after process execution (applies only when the process was started from a user window) */
	public void setRefreshAllAfterExecution(final boolean refreshAllAfterExecution)
	{
		this.refreshAllAfterExecution = refreshAllAfterExecution;
	}

	/**
	 * @return if the whole window tab shall be refreshed after process execution (applies only when the process was started from a user window)
	 */
	public boolean isRefreshAllAfterExecution()
	{
		return refreshAllAfterExecution;
	}

	/**
	 * @param record to be refreshed after process execution
	 */
	public void setRecordToRefreshAfterExecution(final TableRecordReference recordToRefreshAfterExecution)
	{
		this.recordToRefreshAfterExecution = recordToRefreshAfterExecution;
	}

	public TableRecordReference getRecordToRefreshAfterExecution()
	{
		return recordToRefreshAfterExecution;
	}

	/**
	 * @return the record to be selected in window, after this process is executed (applies only when the process was started from a user window).
	 */
	public TableRecordReference getRecordToSelectAfterExecution()
	{
		return recordToSelectAfterExecution;
	}

	/**
	 * Sets the record to be selected in window, after this process is executed (applies only when the process was started from a user window).
	 *
	 * @param recordToSelectAfterExecution
	 */
	public void setRecordToSelectAfterExecution(final TableRecordReference recordToSelectAfterExecution)
	{
		this.recordToSelectAfterExecution = recordToSelectAfterExecution;
	}

	public void setRecordsToOpen(final Collection<TableRecordReference> records, final int adWindowId)
	{
		setRecordsToOpen(records, String.valueOf(adWindowId));
	}

	public void setRecordsToOpen(final Collection<TableRecordReference> records, final String adWindowId)
	{
		if (records == null || records.isEmpty())
		{
			recordsToOpen = null;
		}
		else
		{
			recordsToOpen = new RecordsToOpen(records, adWindowId, OpenTarget.GridView);
		}
	}

	public void setRecordsToOpen(@NonNull final String tableName, final Collection<Integer> recordIds, final String adWindowId)
	{
		if (recordIds == null || recordIds.isEmpty())
		{
			recordsToOpen = null;
		}
		else
		{
			final Set<TableRecordReference> records = recordIds.stream()
					.map(recordId -> TableRecordReference.of(tableName, recordId))
					.collect(ImmutableSet.toImmutableSet());
			recordsToOpen = new RecordsToOpen(records, adWindowId, OpenTarget.GridView);
		}
	}

	public void setRecordsToOpen(final Collection<TableRecordReference> records)
	{
		if (records == null || records.isEmpty())
		{
			recordsToOpen = null;
		}
		else
		{
			final String adWindowId = null;
			recordsToOpen = new RecordsToOpen(records, adWindowId, OpenTarget.GridView);
		}
	}

	public void setRecordToOpen(final TableRecordReference record, final int adWindowId, final OpenTarget target)
	{
		setRecordToOpen(record, String.valueOf(adWindowId), target);
	}

	public void setRecordToOpen(final TableRecordReference record, final String adWindowId, final OpenTarget target)
	{
		if (record == null)
		{
			recordsToOpen = null;
		}
		else
		{
			recordsToOpen = new RecordsToOpen(ImmutableList.of(record), adWindowId, target);
		}
	}

	public RecordsToOpen getRecordsToOpen()
	{
		return recordsToOpen;
	}

	/**
	 * Sets webui's viewId on which this process was executed.
	 */
	public void setWebuiViewId(String webuiViewId)
	{
		this.webuiViewId = webuiViewId;
	}

	public String getWebuiViewId()
	{
		return webuiViewId;
	}

	public void setPrintFormat(final MPrintFormat printFormat)
	{
		this.printFormat = printFormat;
	}

	public MPrintFormat getPrintFormat()
	{
		return printFormat;
	}

	public void setReportData(final byte[] data, final String filename, final String contentType)
	{
		reportData = data;
		reportFilename = filename;
		reportContentType = contentType;
	}

	public void setReportData(@NonNull final File file)
	{
		reportData = Util.readBytes(file);
		reportFilename = file.getName();
		reportContentType = MimeType.getMimeType(reportFilename);
	}

	public byte[] getReportData()
	{
		return reportData;
	}

	public String getReportFilename()
	{
		return reportFilename;
	}

	public String getReportContentType()
	{
		return reportContentType;
	}

	/**
	 * Set Log of Process.
	 *
	 * <pre>
	 *  - Translated Process Message
	 *  - List of log entries
	 *      Date - Number - Msg
	 * </pre>
	 *
	 * @param html if true with HTML markup
	 * @return Log Info
	 */
	public String getLogInfo(final boolean html)
	{
		final List<ProcessInfoLog> logs = getLogsInnerList();
		if (logs.isEmpty())
		{
			return "";
		}

		//
		final StringBuilder sb = new StringBuilder();
		final SimpleDateFormat dateFormat = DisplayType.getDateFormat(DisplayType.DateTime);
		if (html)
		{
			sb.append("<table width=\"100%\" border=\"1\" cellspacing=\"0\" cellpadding=\"2\">");
		}
		//
		for (final ProcessInfoLog log : logs)
		{
			if (html)
			{
				sb.append("<tr>");
			}
			else
			{
				sb.append("\n");
			}

			//
			if (log.getP_Date() != null)
			{
				sb.append(html ? "<td>" : "")
						.append(dateFormat.format(log.getP_Date()))
						.append(html ? "</td>" : " \t");
			}
			//
			if (log.getP_Number() != null)
			{
				sb.append(html ? "<td>" : "")
						.append(log.getP_Number())
						.append(html ? "</td>" : " \t");
			}
			//
			if (log.getP_Msg() != null)
			{
				sb.append(html ? "<td>" : "")
						.append(Services.get(IMsgBL.class).parseTranslation(Env.getCtx(), log.getP_Msg()))
						.append(html ? "</td>" : "");
			}
			//
			if (html)
			{
				sb.append("</tr>");
			}
		}
		if (html)
		{
			sb.append("</table>");
		}
		return sb.toString();
	}	// getLogInfo

	/**
	 * Get ASCII Log Info
	 *
	 * @return Log Info
	 */
	public String getLogInfo()
	{
		return getLogInfo(false);
	}

	/**
	 * Gets current logs.
	 *
	 * If needed, it will load the logs.
	 *
	 * @return logs inner list; never fails
	 */
	private final List<ProcessInfoLog> getLogsInnerList()
	{
		if (logs == null)
		{
			try
			{
				logs = new ArrayList<>(Services.get(IADPInstanceDAO.class).retrieveProcessInfoLogs(getPinstanceId()));
			}
			catch (final Exception ex)
			{
				// Don't fail log lines failed loading because most of the APIs rely on this.
				// In case we would propagate the exception we would face:
				// * worst case would be that it will stop some important execution.
				// * best case the exception would be lost somewhere without any notification
				logs = new ArrayList<>();
				logs.add(ProcessInfoLog.ofMessage("Ops, sorry we failed loading the log lines. (details in console)"));
				logger.warn("Failed loading log lines for {}", this, ex);
			}
		}
		return logs;
	}

	/**
	 * Get current logs (i.e. logs which were recorded to this instance).
	 *
	 * This method will not load the logs.
	 *
	 * @return current logs
	 */
	public List<ProcessInfoLog> getCurrentLogs()
	{
		// NOTE: don't load them!
		final List<ProcessInfoLog> logs = this.logs;
		return logs == null ? ImmutableList.of() : ImmutableList.copyOf(logs);
	}

	public void markLogsAsStale()
	{
		// TODO: shall we save existing ones ?!
		logs = null;
	}

	/**************************************************************************
	 * Add to Log
	 *
	 * @param Log_ID Log ID
	 * @param P_ID Process ID
	 * @param P_Date Process Date
	 * @param P_Number Process Number
	 * @param P_Msg Process Message
	 */
	public void addLog(final int Log_ID, final Timestamp P_Date, final BigDecimal P_Number, final String P_Msg)
	{
		addLog(new ProcessInfoLog(Log_ID, P_Date, P_Number, P_Msg));
	}	// addLog

	public void addLog(final RepoIdAware Log_ID, final Timestamp P_Date, final BigDecimal P_Number, final String P_Msg)
	{
		addLog(new ProcessInfoLog(Log_ID != null ? Log_ID.getRepoId() : -1, P_Date, P_Number, P_Msg));
	}	// addLog

	/**
	 * Add to Log.
	 *
	 * @param P_ID Process ID
	 * @param P_Date Process Date if <code>null</code> then the current {@link SystemTime} is used.
	 * @param P_Number Process Number
	 * @param P_Msg Process Message
	 */
	public void addLog(final Timestamp P_Date, final BigDecimal P_Number, final String P_Msg)
	{
		final Timestamp timestampToUse = P_Date != null ? P_Date : SystemTime.asTimestamp();

		addLog(new ProcessInfoLog(timestampToUse, P_Number, P_Msg));
	}	// addLog

	/**
	 * Add to Log
	 *
	 * @param logEntry log entry
	 */
	public void addLog(final ProcessInfoLog logEntry)
	{
		if (logEntry == null)
		{
			return;
		}

		final List<ProcessInfoLog> logs;
		if (this.logs == null)
		{
			logs = this.logs = new ArrayList<>();
		}
		else
		{
			logs = this.logs;
		}

		logs.add(logEntry);
	}

	public void propagateErrorIfAny()
	{
		if (!isError())
		{
			return;
		}

		final Throwable throwable = getThrowable();
		if (throwable != null)
		{
			throw AdempiereException.wrapIfNeeded(throwable);
		}
		else
		{
			throw new AdempiereException(getSummary());
		}
	}

	public void updateFrom(final ProcessExecutionResult otherResult)
	{
		summary = otherResult.getSummary();
		error = otherResult.isError();
		throwable = otherResult.getThrowable();

		errorWasReportedToUser = otherResult.isErrorWasReportedToUser();

		timeout = otherResult.isTimeout();

		// Logs
		markLogsAsStale(); // IMPORTANT: not copying them because they are transient
		showProcessLogsPolicy = otherResult.showProcessLogsPolicy;

		//
		// Reporting
		printFormat = otherResult.printFormat;
		reportData = otherResult.reportData;
		reportFilename = otherResult.reportFilename;
		reportContentType = otherResult.reportContentType;

		refreshAllAfterExecution = otherResult.refreshAllAfterExecution;

		recordToSelectAfterExecution = otherResult.recordToSelectAfterExecution;
		recordsToOpen = otherResult.recordsToOpen;
		webuiViewToOpen = otherResult.webuiViewToOpen;
		displayQRCode = otherResult.displayQRCode;
	}

	public void setDisplayQRCodeFromString(final String qrCode)
	{
		setDisplayQRCode(DisplayQRCode.builder().code(qrCode).build());
	}

	//
	//
	//
	//

	@Immutable
	@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
	public static final class RecordsToOpen
	{
		@JsonProperty("records")
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		private final List<TableRecordReference> records;

		@JsonProperty("adWindowId")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		private final String adWindowIdStr;

		public static enum OpenTarget
		{
			SingleDocument, SingleDocumentModal, GridView,
		}

		@JsonProperty("target")
		private final OpenTarget target;

		@JsonCreator
		private RecordsToOpen( //
				@JsonProperty("records") final Collection<TableRecordReference> records //
				, @JsonProperty("adWindowId") final String adWindowId //
				, @JsonProperty("target") final OpenTarget target //
		)
		{
			Check.assumeNotEmpty(records, "records is not empty");

			this.records = ImmutableList.copyOf(records);
			this.adWindowIdStr = Check.isEmpty(adWindowId, true) ? null : adWindowId;
			this.target = target;
		}

		@Override
		public String toString()
		{
			return MoreObjects.toStringHelper(this)
					.omitNullValues()
					.add("records", records)
					.add("adWindowId", adWindowIdStr)
					.add("target", target)
					.toString();
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(records, adWindowIdStr, target);
		}

		@Override
		public boolean equals(final Object obj)
		{
			if (this == obj)
			{
				return true;
			}
			if (obj instanceof RecordsToOpen)
			{
				final RecordsToOpen other = (RecordsToOpen)obj;
				return Objects.equals(records, other.records)
						&& Objects.equals(adWindowIdStr, other.adWindowIdStr)
						&& Objects.equals(target, other.target);
			}
			else
			{
				return false;
			}
		}

		/** @return records to open; never null or empty */
		public List<TableRecordReference> getRecords()
		{
			return records;
		}

		public TableRecordReference getSingleRecord()
		{
			return records.get(0);
		}

		public String getWindowIdString()
		{
			return adWindowIdStr;
		}

		public OpenTarget getTarget()
		{
			return target;
		}
	}

	//
	//
	//
	//
	//

	public static enum ViewOpenTarget
	{
		IncludedView, ModalOverlay, NewBrowserTab
	}

	@Immutable
	@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
	@lombok.Value
	public static final class WebuiViewToOpen
	{
		@JsonProperty("viewId")
		String viewId;
		@JsonProperty("profileId")
		String profileId;
		@JsonProperty("target")
		ViewOpenTarget target;

		@lombok.Builder
		@JsonCreator
		private WebuiViewToOpen(
				@JsonProperty("viewId") @NonNull final String viewId,
				@JsonProperty("profileId") final String profileId,
				@JsonProperty("target") @NonNull final ViewOpenTarget target)
		{
			this.viewId = viewId;
			this.profileId = profileId;
			this.target = target;
		}
	}

	@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
	@lombok.Value
	public static final class DisplayQRCode
	{
		@JsonProperty("code")
		String code;

		@lombok.Builder
		@JsonCreator
		private DisplayQRCode(@JsonProperty("code") @NonNull final String code)
		{
			this.code = code;
		}
	}
}
