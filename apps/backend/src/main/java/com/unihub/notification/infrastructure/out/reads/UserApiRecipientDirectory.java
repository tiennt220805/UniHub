package com.unihub.notification.infrastructure.out.reads;

import com.unihub.notification.application.port.out.Recipient;
import com.unihub.notification.application.port.out.RecipientDirectory;
import com.unihub.shared.domain.id.UserId;
import com.unihub.user.api.UserContact;
import com.unihub.user.api.UserContacts;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** {@link RecipientDirectory} over {@code user.api} (Phase 4 checklist D35): translation only. */
@Component
class UserApiRecipientDirectory implements RecipientDirectory {
    private final UserContacts contacts;

    UserApiRecipientDirectory(UserContacts contacts) {
        this.contacts = contacts;
    }

    @Override
    public Optional<Recipient> find(UUID userId) {
        return contacts.find(UserId.of(userId)).map(UserApiRecipientDirectory::recipient);
    }

    @Override
    public Map<UUID, Recipient> findAll(Collection<UUID> userIds) {
        var found = new LinkedHashMap<UUID, Recipient>();
        contacts.findAll(userIds.stream().map(UserId::of).toList())
                .forEach((id, contact) -> found.put(id.value(), recipient(contact)));
        return found;
    }

    private static Recipient recipient(UserContact contact) {
        return new Recipient(contact.id().value(), contact.fullName(), contact.email());
    }
}
