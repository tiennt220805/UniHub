package com.unihub.user.application.usecase;

import com.unihub.shared.application.routing.ReadFromPrimary;
import com.unihub.shared.domain.id.UserId;
import com.unihub.user.api.UserContact;
import com.unihub.user.api.UserContacts;
import com.unihub.user.application.port.out.UserContactQueries;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserContactLookup implements UserContacts {
    private final UserContactQueries contacts;

    UserContactLookup(UserContactQueries contacts) {
        this.contacts = contacts;
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public Optional<UserContact> find(UserId id) {
        return contacts.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    @ReadFromPrimary
    public Map<UserId, UserContact> findAll(Collection<UserId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return contacts.findAllById(ids);
    }
}
