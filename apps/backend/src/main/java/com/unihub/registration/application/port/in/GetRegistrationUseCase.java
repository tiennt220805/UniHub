package com.unihub.registration.application.port.in;

import com.unihub.registration.application.query.GetRegistrationQuery;
import com.unihub.registration.application.result.RegistrationResult;

public interface GetRegistrationUseCase {

    /**
     * @throws com.unihub.registration.domain.exception.RegistrationNotFoundException when there is
     *     none
     * @throws com.unihub.registration.domain.exception.RegistrationNotOwnedException when it is
     *     someone else's
     */
    RegistrationResult get(GetRegistrationQuery query);
}
