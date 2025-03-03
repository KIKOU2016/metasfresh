package de.metas.util.lang;

import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.annotation.Nullable;

import de.metas.util.Check;
import de.metas.util.NumberUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.util
 * %%
 * Copyright (C) 2018 metas GmbH
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

@Value
public class Percent
{
	public static Percent of(@NonNull final String value)
	{
		return of(new BigDecimal(value));
	}

	/**
	 * @param value 100 based percent
	 */
	public static Percent of(@NonNull final BigDecimal value)
	{
		if (value.signum() == 0)
		{
			return ZERO;
		}
		else if (ONE_HUNDRED.toBigDecimal().compareTo(value) == 0)
		{
			return ONE_HUNDRED;
		}
		else
		{
			return new Percent(value);
		}
	}

	public static Percent ofNullable(final BigDecimal value)
	{
		return value != null ? of(value) : null;
	}

	/**
	 * @param value 100 based percent
	 */
	public static Percent of(final int value)
	{
		return of(BigDecimal.valueOf(value));
	}

	/**
	 * Convenience method, similar to {@link #of(BigDecimal, BigDecimal, int)}, but the {@code numerator} is {@code compareValue - baseValue}.
	 * <p>
	 * Examples:
	 * <li>{@code Percent.ofDelta(BigDecimal.ONE, new BigDecimal("1.2"))} returns an instance of "20%".
	 * <li>{@code Percent.ofDelta(BigDecimal.ONE, new BigDecimal("0.75"))} returns an instance of "-25%".
	 **/
	public static Percent ofDelta(@NonNull final BigDecimal baseValue, @NonNull final BigDecimal compareValue)
	{
		return of(compareValue.subtract(baseValue), baseValue, 2);
	}

	/**
	 * Like {@link #of(BigDecimal, BigDecimal, int)} with a scale of 2.
	 */
	public static Percent of(@NonNull final BigDecimal numerator, @NonNull final BigDecimal denominator)
	{
		return of(numerator, denominator, 2);
	}

	/**
	 * Examples:
	 * <li>{@code Percent.of(BigDecimal.ONE, new BigDecimal("4"), 2)} returns an instance of "25%".
	 * <li>{@code Percent.of(BigDecimal.ONE, new BigDecimal("3"), 2)} returns an instance of "33.33%".
	 *
	 * @param denominator if zero, then {@value #ZERO} percent is returned.
	 * @return a percent instance with max. two digits after the decimal point.
	 */
	public static Percent of(
			@NonNull final BigDecimal numerator,
			@NonNull final BigDecimal denominator,
			final int precision)
	{
		Check.assumeGreaterOrEqualToZero(precision, "precision");

		if (denominator.signum() == 0)
		{
			return ZERO;
		}

		final int scale = precision + 2; // +2 because i guess if we multiply by 100 in the end, everything shifts by two digits

		final BigDecimal percentValue = numerator
				.setScale(scale, RoundingMode.HALF_UP)
				.divide(denominator, RoundingMode.HALF_UP)
				.multiply(ONE_HUNDRED_VALUE);

		return Percent.of(percentValue);
	}

	public static BigDecimal toBigDecimalOrNull(@Nullable final Percent paymentDiscountOverrideOrNull)
	{
		if (paymentDiscountOverrideOrNull == null)
		{
			return null;
		}
		return paymentDiscountOverrideOrNull.toBigDecimal();
	}

	private static final BigDecimal ONE_HUNDRED_VALUE = BigDecimal.valueOf(100);
	public static final Percent ONE_HUNDRED = new Percent(ONE_HUNDRED_VALUE);

	private static final BigDecimal TWO_VALUE = new BigDecimal("2");

	public static final Percent ZERO = new Percent(BigDecimal.ZERO);

	@Getter(AccessLevel.NONE)
	private final BigDecimal value;

	private Percent(@NonNull final BigDecimal valueAsBigDecimal)
	{
		// NOTE: important to strip the trailing zeros, else the hashCode and equals might have different results
		this.value = NumberUtils.stripTrailingDecimalZeros(valueAsBigDecimal);
	}

	public BigDecimal toBigDecimal()
	{
		return value;
	}

	public int toInt()
	{
		return value.intValue();
	}

	public boolean isZero()
	{
		return value.signum() == 0;
	}

	public int signum()
	{
		return value.signum();
	}

	public boolean isOneHundred()
	{
		return ONE_HUNDRED_VALUE.compareTo(value) == 0;
	}

	public Percent add(@NonNull final Percent percent)
	{
		if (isZero())
		{
			return percent;
		}
		else if (percent.isZero())
		{
			return this;
		}
		else
		{
			return of(value.add(percent.value));
		}
	}

	public Percent subtract(@NonNull final Percent percent)
	{
		if (percent.isZero())
		{
			return this;
		}
		return of(this.value.subtract(percent.value));
	}

	public Percent multiply(@NonNull final Percent percent, int precision)
	{
		if (isOneHundred())
		{
			return percent;
		}
		else if (percent.isOneHundred())
		{
			return this;
		}
		else
		{
			return of(this.value.multiply(percent.value).divide(ONE_HUNDRED_VALUE, precision, RoundingMode.UP));
		}
	}

	/**
	 * Example:
	 * <li>{@code Percent.of(ONE).multiply(new BigDecimal("200"))} returns {@code 2}.
	 *
	 * @param precision scale of the result; may be less than the scale of the given {@code base}
	 */
	public BigDecimal multiply(@NonNull final BigDecimal base, final int precision)
	{
		Check.assumeGreaterOrEqualToZero(precision, "precision");

		if (base.signum() == 0)
		{
			return BigDecimal.ZERO;
		}
		else if (isZero())
		{
			return BigDecimal.ZERO;
		}
		else if (isOneHundred())
		{
			return base.setScale(precision, RoundingMode.HALF_UP);
		}
		else
		{
			return base
					.setScale(precision + 2, RoundingMode.HALF_UP)
					.divide(ONE_HUNDRED_VALUE, RoundingMode.HALF_UP)
					.multiply(value)
					.setScale(precision, RoundingMode.HALF_UP);
		}
	}

	public BigDecimal addToBase(@NonNull final BigDecimal base, final int precision)
	{
		Check.assumeGreaterOrEqualToZero(precision, "precision");

		if (base.signum() == 0)
		{
			return BigDecimal.ZERO;
		}
		else if (isZero())
		{
			return base.setScale(precision, RoundingMode.HALF_UP);
		}
		else
		{
			// make sure the base we work with does not have more digits than we expect from the given precision.
			final BigDecimal baseToUse = base.setScale(precision, RoundingMode.HALF_UP);

			return baseToUse
					.setScale(precision + 2)
					.divide(ONE_HUNDRED_VALUE, RoundingMode.UNNECESSARY) // no rounding needed because we raised the current precision by 2
					.multiply(ONE_HUNDRED_VALUE.add(value))
					.setScale(precision, RoundingMode.HALF_UP);
		}
	}

	/**
	 * Example: {@code Percent.of(TEN).subtractFromBase(new BigDecimal("100"), 0)} equals to 90
	 */
	public BigDecimal subtractFromBase(@NonNull final BigDecimal base, final int precision)
	{
		Check.assumeGreaterOrEqualToZero(precision, "precision");

		if (base.signum() == 0)
		{
			return BigDecimal.ZERO;
		}
		else if (isZero())
		{
			return base.setScale(precision, RoundingMode.HALF_UP);
		}
		else if (isOneHundred())
		{
			return BigDecimal.ZERO;
		}
		else
		{
			// make sure the base we work with does not have more digits than we expect from the given precision.
			final BigDecimal baseToUse = base.setScale(precision, RoundingMode.HALF_UP);

			return baseToUse
					.setScale(precision + 2)
					.divide(ONE_HUNDRED_VALUE, RoundingMode.UNNECESSARY) // no rounding needed because we raised the current precision by 2
					.multiply(ONE_HUNDRED_VALUE.subtract(value))
					.setScale(precision, RoundingMode.HALF_UP);
		}
	}

	/**
	 * Round to the nearest {@code .5%} percent value.
	 */
	public Percent roundToHalf(@NonNull final RoundingMode roundingMode)
	{
		final BigDecimal newPercentValue = toBigDecimal()
				.multiply(TWO_VALUE)
				.setScale(0, roundingMode)
				.divide(TWO_VALUE)
				.setScale(1, roundingMode); // AFAIU not needed, but who knows..

		return Percent.of(newPercentValue);
	}
}
