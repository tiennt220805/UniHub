package com.unihub.payment.application.port.in;

import com.unihub.payment.application.command.ChargePaymentCommand;
import com.unihub.payment.application.result.ChargePaymentResult;

public interface ChargePaymentUseCase {

    ChargePaymentResult charge(ChargePaymentCommand command);
}
