package com.unihub.notification.application.port.in;

import com.unihub.notification.application.query.ListMyNotificationsQuery;
import com.unihub.notification.application.result.MyNotificationsResult;

public interface ListMyNotificationsUseCase {

    MyNotificationsResult list(ListMyNotificationsQuery query);
}
