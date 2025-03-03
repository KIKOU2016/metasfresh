/**
 *
 */
package de.metas.adempiere.report.jasper;

import javax.annotation.Nullable;

import de.metas.report.engine.ReportContext;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * report-service
 * %%
 * Copyright (C) 2019 metas GmbH
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

/**
 * @author metas-dev <dev@metasfresh.com>
 *
 */
@Value
@Builder
final public class JsonDataSourceRequest
{
	@NonNull final ReportContext reportContext;
	@Nullable final String authenticationToken;
}
