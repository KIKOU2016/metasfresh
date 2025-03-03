package org.adempiere.util.api;

/*
 * #%L
 * de.metas.util
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;

/**
 * No parameters implementation of {@link IParams}. Get your instance using {@link IParams#NULL}.
 *
 * @author tsa
 *
 */
/* package */final class NullParams implements IParams
{
	public static final transient NullParams instance = new NullParams();

	private NullParams()
	{
	}

	@Override
	public boolean hasParameter(final String parameterName)
	{
		return false;
	}

	@Override
	public Object getParameterAsObject(final String parameterName)
	{
		return null;
	}

	@Override
	public String getParameterAsString(final String parameterName)
	{
		return null;
	}

	@Override
	public int getParameterAsInt(final String parameterName, final int defaultValue)
	{
		return defaultValue;
	}

	@Override
	public boolean getParameterAsBool(final String parameterName)
	{
		return false;
	}

	@Override
	public Timestamp getParameterAsTimestamp(final String parameterName)
	{
		return null;
	}

	@Override
	public LocalDate getParameterAsLocalDate(final String parameterName)
	{
		return null;
	}

	@Override
	public BigDecimal getParameterAsBigDecimal(final String paraCheckNetamttoinvoice)
	{
		return null;
	}

	/**
	 * Returns an empty list.
	 */
	@Override
	public Collection<String> getParameterNames()
	{
		return Collections.emptyList();
	}

	@Override
	public <T extends Enum<T>> T getParameterAsEnum(final String parameterName, final Class<T> enumType, final T defaultValueWhenNull)
	{
		return defaultValueWhenNull;
	}
}
