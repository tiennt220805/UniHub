package com.unihub.registration.application.port.in;

import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.result.ReserveSeatResult;

public interface ReserveSeatUseCase {

    ReserveSeatResult reserve(ReserveSeatCommand command);
}
