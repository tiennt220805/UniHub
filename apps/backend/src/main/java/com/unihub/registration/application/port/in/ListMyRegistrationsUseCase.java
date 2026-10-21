package com.unihub.registration.application.port.in;

import com.unihub.registration.application.query.ListMyRegistrationsQuery;
import com.unihub.registration.application.result.MyRegistrationsResult;

public interface ListMyRegistrationsUseCase {

    MyRegistrationsResult list(ListMyRegistrationsQuery query);
}
