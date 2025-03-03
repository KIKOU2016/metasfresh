/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved. *
 * This program is free software; you can redistribute it and/or modify it *
 * under the terms version 2 of the GNU General Public License as published *
 * by the Free Software Foundation. This program is distributed in the hope *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. *
 * See the GNU General Public License for more details. *
 * You should have received a copy of the GNU General Public License along *
 * with this program; if not, write to the Free Software Foundation, Inc., *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA. *
 * For the text or an alternative of this public license, you may reach us *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA *
 * or via info@compiere.org or http://www.compiere.org/license.html *
 *****************************************************************************/
package de.metas.process;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.function.Function;

import org.compiere.util.DisplayType;
import org.compiere.util.TimeUtil;

import de.metas.util.lang.RepoIdAware;
import lombok.NonNull;

/**
 * Immutable Process Parameter
 *
 * @author Jorg Janke
 * @version $Id: ProcessInfoParameter.java,v 1.2 2006/07/30 00:54:44 jjanke Exp $
 *
 * @author Teo Sarca, www.arhipac.ro
 *         <li>FR [ 2430845 ] Add ProcessInfoParameter.getParameterAsBoolean method
 */
public final class ProcessInfoParameter implements Serializable
{
	/**
	 *
	 */
	private static final long serialVersionUID = 4536416337960754407L;

	public static ProcessInfoParameter of(final String parameterName, final int parameterValue)
	{
		final Integer parameterValueTo = null;
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public static ProcessInfoParameter of(final String parameterName, final String parameterValue)
	{
		final String parameterValueTo = null;
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public static ProcessInfoParameter of(final String parameterName, final BigDecimal parameterValue)
	{
		final BigDecimal parameterValueTo = null;
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public static ProcessInfoParameter of(final String parameterName, final java.util.Date parameterValue)
	{
		final java.util.Date parameterValueTo = null;
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public static ProcessInfoParameter of(final String parameterName, final java.util.Date parameterValue, final java.util.Date parameterValueTo)
	{
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public static ProcessInfoParameter of(final String parameterName, final boolean parameterValue)
	{
		final Boolean parameterValueTo = null;
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public static ProcessInfoParameter ofValueObject(final String parameterName, final Object parameterValue)
	{
		final Object parameterValueTo = null;
		final String info = null;
		final String info_To = null;
		return new ProcessInfoParameter(parameterName, parameterValue, parameterValueTo, info, info_To);
	}

	public ProcessInfoParameter(
			final String parameterName,
			final Object parameter,
			final Object parameter_To,
			final String info,
			final String info_To)
	{
		m_ParameterName = parameterName;
		m_Parameter = parameter;
		m_Parameter_To = parameter_To;
		m_Info = info == null ? "" : info;
		m_Info_To = info_To == null ? "" : info_To;
	}

	private final String m_ParameterName;
	private final Object m_Parameter;
	private final Object m_Parameter_To;
	private final String m_Info;
	private final String m_Info_To;

	@Override
	public String toString()
	{
		// From .. To
		if (m_Parameter_To != null || m_Info_To.length() > 0)
		{
			return "ProcessInfoParameter[" + m_ParameterName + "=" + m_Parameter
					+ (m_Parameter == null ? "" : "{" + m_Parameter.getClass().getName() + "}")
					+ " (" + m_Info + ") - "
					+ m_Parameter_To
					+ (m_Parameter_To == null ? "" : "{" + m_Parameter_To.getClass().getName() + "}")
					+ " (" + m_Info_To + ")";
		}
		// Value
		else
		{
			return "ProcessInfoParameter[" + m_ParameterName + "=" + m_Parameter
					+ (m_Parameter == null ? "" : "{" + m_Parameter.getClass().getName() + "}")
					+ " (" + m_Info + ")";
		}
	}

	public String getParameterName()
	{
		return m_ParameterName;
	}

	public String getInfo()
	{
		return m_Info;
	}

	public String getInfo_To()
	{
		return m_Info_To;
	}

	public Object getParameter()
	{
		return m_Parameter;
	}

	public Object getParameter_To()
	{
		return m_Parameter_To;
	}

	public String getParameterAsString()
	{
		return toString(m_Parameter);
	}

	public String getParameter_ToAsString()
	{
		return toString(m_Parameter_To);
	}

	private static String toString(final Object value)
	{
		if (value == null)
		{
			return null;
		}
		return value.toString();
	}

	public int getParameterAsInt()
	{
		return getParameterAsInt(0);
	}

	public int getParameterAsInt(final int defaultValueWhenNull)
	{
		return toInt(m_Parameter, defaultValueWhenNull);
	}

	public <T extends RepoIdAware> T getParameterAsRepoId(@NonNull final Function<Integer, T> mapper)
	{
		return mapper.apply(getParameterAsInt(-1));
	}

	public int getParameter_ToAsInt()
	{
		return getParameter_ToAsInt(0);
	}

	public int getParameter_ToAsInt(final int defaultValueWhenNull)
	{
		return toInt(m_Parameter_To, defaultValueWhenNull);
	}

	private static int toInt(final Object value, final int defaultValueWhenNull)
	{
		if (value == null)
		{
			return defaultValueWhenNull;
		}
		else if (value instanceof Number)
		{
			return ((Number)value).intValue();
		}
		else if (value instanceof RepoIdAware)
		{
			return ((RepoIdAware)value).getRepoId();
		}
		else
		{
			final BigDecimal bd = new BigDecimal(value.toString());
			return bd.intValue();
		}
	}

	public boolean getParameterAsBoolean()
	{
		final boolean defaultValue = false;
		return toBoolean(m_Parameter, defaultValue);
	}

	public Boolean getParameterAsBooleanOrNull()
	{
		final Boolean defaultValue = null;
		return toBoolean(m_Parameter, defaultValue);
	}

	public boolean getParameter_ToAsBoolean()
	{
		final boolean defaultValue = false;
		return toBoolean(m_Parameter_To, defaultValue);
	}

	private static Boolean toBoolean(final Object value, final Boolean defaultValue)
	{
		return DisplayType.toBoolean(value, defaultValue);
	}

	public Timestamp getParameterAsTimestamp()
	{
		return TimeUtil.asTimestamp(m_Parameter);
	}

	public Timestamp getParameter_ToAsTimestamp()
	{
		return TimeUtil.asTimestamp(m_Parameter_To);
	}

	public LocalDate getParameterAsLocalDate()
	{
		return TimeUtil.asLocalDate(m_Parameter);
	}

	public LocalDate getParameter_ToAsLocalDate()
	{
		return TimeUtil.asLocalDate(m_Parameter_To);
	}

	public BigDecimal getParameterAsBigDecimal()
	{
		return toBigDecimal(m_Parameter);
	}

	public BigDecimal getParameter_ToAsBigDecimal()
	{
		return toBigDecimal(m_Parameter_To);
	}

	private static BigDecimal toBigDecimal(final Object value)
	{
		if (value == null)
		{
			return null;
		}
		else if (value instanceof BigDecimal)
		{
			return (BigDecimal)value;
		}
		else if (value instanceof Integer)
		{
			return BigDecimal.valueOf((Integer)value);
		}
		else
		{
			final BigDecimal bd = new BigDecimal(value.toString());
			return bd;
		}
	}
}   // ProcessInfoParameter
