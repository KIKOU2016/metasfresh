package org.eevolution.event;

import java.time.Instant;
import java.util.Collection;

import org.eevolution.model.I_PP_Order;
import org.eevolution.mrp.spi.impl.pporder.PPOrderProducer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import de.metas.Profiles;
import de.metas.material.event.MaterialEventHandler;
import de.metas.material.event.pporder.PPOrder;
import de.metas.material.event.pporder.PPOrderRequestedEvent;
import lombok.NonNull;

/*
 * #%L
 * de.metas.adempiere.libero.libero
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

@Service
@Profile(Profiles.PROFILE_App) // only one handler should bother itself with these events
public class PPOrderRequestedEventHandler implements MaterialEventHandler<PPOrderRequestedEvent>
{
	private final PPOrderProducer ppOrderProducer;

	public PPOrderRequestedEventHandler(@NonNull final PPOrderProducer ppOrderProducer)
	{
		this.ppOrderProducer = ppOrderProducer;
	}

	@Override
	public Collection<Class<? extends PPOrderRequestedEvent>> getHandeledEventType()
	{
		return ImmutableList.of(PPOrderRequestedEvent.class);
	}

	@Override
	public void validateEvent(@NonNull final PPOrderRequestedEvent event)
	{
		event.validate();
	}

	@Override
	public void handleEvent(@NonNull final PPOrderRequestedEvent event)
	{
		createProductionOrder(event);
	}

	/**
	 * Creates a production order. Note that it does not fire an event, because production orders can be created and changed for many reasons,<br>
	 * and therefore we leave the event-firing to a model interceptor.
	 */
	@VisibleForTesting
	I_PP_Order createProductionOrder(@NonNull final PPOrderRequestedEvent ppOrderRequestedEvent)
	{
		final PPOrder ppOrder = ppOrderRequestedEvent.getPpOrder();
		final Instant dateOrdered = ppOrderRequestedEvent.getDateOrdered();

		return ppOrderProducer.createPPOrder(ppOrder, dateOrdered);
	}
}
