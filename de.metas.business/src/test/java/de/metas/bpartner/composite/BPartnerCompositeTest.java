package de.metas.bpartner.composite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import de.metas.bpartner.BPartnerContactId;
import de.metas.bpartner.BPartnerId;
import de.metas.bpartner.BPartnerLocationId;

/*
 * #%L
 * de.metas.business
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

class BPartnerCompositeTest
{

	@Test
	void test()
	{
		final BPartnerId bpartnerId = BPartnerId.ofRepoId(10);
		final BPartnerContactId bpartnerContactId = BPartnerContactId.ofRepoId(bpartnerId, 10);

		final BPartnerContact contact = BPartnerContact.builder()
				.id(bpartnerContactId)
				.build();

		final BPartnerLocation location = BPartnerLocation.builder()
				.id(BPartnerLocationId.ofRepoId(bpartnerId, 10))
				.build();

		final BPartnerComposite bpartnerComposite = BPartnerComposite.builder()
				.contact(contact)
				.location(location)
				.build();

		assertThat(bpartnerComposite.getContact(bpartnerContactId)).contains(contact);
	}

}
