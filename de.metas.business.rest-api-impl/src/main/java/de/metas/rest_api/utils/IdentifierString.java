package de.metas.rest_api.utils;

import static de.metas.util.Check.assumeNotEmpty;

import java.util.function.IntFunction;

import org.adempiere.exceptions.AdempiereException;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import de.metas.bpartner.GLN;
import de.metas.rest_api.JsonExternalId;
import de.metas.rest_api.MetasfreshId;
import de.metas.util.Check;
import de.metas.util.lang.RepoIdAware;
import de.metas.util.rest.ExternalId;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.business.rest-api-impl
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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

/** Identifies a metasfresh resourse (e.g. business partner) */
@Value
public class IdentifierString
{

	public enum Type
	{
		/** Every metasfresh ressource can be identifies via its metasfresh-ID (i.e. the PK of its data base record) */
		METASFRESH_ID,

		EXTERNAL_ID, VALUE, GLN
	}

	Type type;

	@Getter(AccessLevel.NONE)
	String value;

	public static final String PREFIX_EXTERNAL_ID = "ext-";
	public static final String PREFIX_VALUE = "val-";
	public static final String PREFIX_GLN = "gln-";

	@JsonCreator
	public static final IdentifierString of(@NonNull final String value)
	{
		assumeNotEmpty("Parameter may not be empty", value);
		if (value.toLowerCase().startsWith(PREFIX_EXTERNAL_ID))
		{
			final String externalId = value.substring(4).trim();
			if (externalId.isEmpty())
			{
				throw new AdempiereException("Invalid external ID: `" + value + "`");
			}
			return new IdentifierString(Type.EXTERNAL_ID, externalId);
		}
		else if (value.toLowerCase().startsWith(PREFIX_VALUE))
		{
			final String valueString = value.substring(4).trim();
			if (valueString.isEmpty())
			{
				throw new AdempiereException("Invalid value: `" + value + "`");
			}
			return new IdentifierString(Type.VALUE, valueString);
		}
		else if (value.toLowerCase().startsWith(PREFIX_GLN))
		{
			final String glnString = value.substring(4).trim();
			if (glnString.isEmpty())
			{
				throw new AdempiereException("Invalid GLN: `" + value + "`");
			}
			return new IdentifierString(Type.GLN, glnString);
		}
		else
		{
			try
			{
				final int repoId = Integer.parseInt(value);
				if (repoId <= 0) // there is an AD_User with AD_User_ID=0, but we don't want the endpoint to provide it anyways
				{
					throw new InvalidIdentifierException(value);
				}

				return new IdentifierString(Type.METASFRESH_ID, value);
			}
			catch (final NumberFormatException ex)
			{
				throw new InvalidIdentifierException(value, ex);
			}
		}
	}

	private IdentifierString(
			@NonNull final Type type,
			@NonNull final String value)
	{
		this.type = type;
		this.value = assumeNotEmpty(value, "Parameter value may not be empty");
	}

	@Override
	@Deprecated
	public String toString()
	{
		// using toJson because it's much more user friendly
		return toJson();
	}

	@JsonValue
	public String toJson()
	{
		final String prefix;
		if (Type.METASFRESH_ID.equals(type))
		{
			prefix = "";
		}
		else if (Type.EXTERNAL_ID.equals(type))
		{
			prefix = PREFIX_EXTERNAL_ID;
		}
		else if (Type.VALUE.equals(type))
		{
			prefix = PREFIX_VALUE;
		}
		else if (Type.GLN.equals(type))
		{
			prefix = PREFIX_GLN;
		}
		else
		{
			throw new AdempiereException("Unknown type: " + type);
		}

		return !prefix.isEmpty() ? prefix + value : value;
	}

	public ExternalId asExternalId()
	{
		Check.assume(Type.EXTERNAL_ID.equals(type), "The type of this instace needs to be {}; this={}", Type.EXTERNAL_ID, this);

		return ExternalId.of(value);
	}

	public JsonExternalId asJsonExternalId()
	{
		Check.assume(Type.EXTERNAL_ID.equals(type), "The type of this instace needs to be {}; this={}", Type.EXTERNAL_ID, this);

		return JsonExternalId.of(value);
	}

	public MetasfreshId asMetasfreshId()
	{
		Check.assume(Type.METASFRESH_ID.equals(type), "The type of this instace needs to be {}; this={}", Type.METASFRESH_ID, this);

		final int repoId = Integer.parseInt(value);
		return MetasfreshId.of(repoId);
	}

	public <T extends RepoIdAware> T asMetasfreshId(@NonNull final IntFunction<T> mapper)
	{
		Check.assume(Type.METASFRESH_ID.equals(type), "The type of this instace needs to be {}; this={}", Type.METASFRESH_ID, this);

		final int repoId = Integer.parseInt(value);
		return mapper.apply(repoId);
	}

	public GLN asGLN()
	{
		Check.assume(Type.GLN.equals(type), "The type of this instace needs to be {}; this={}", Type.GLN, this);

		return GLN.ofString(value);
	}

	public String asValue()
	{
		Check.assume(Type.VALUE.equals(type), "The type of this instace needs to be {}; this={}", Type.VALUE, this);

		return value;
	}
}
