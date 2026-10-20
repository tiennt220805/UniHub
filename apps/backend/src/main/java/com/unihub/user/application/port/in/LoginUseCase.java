package com.unihub.user.application.port.in;

import com.unihub.user.application.command.LoginCommand;
import com.unihub.user.application.result.LoginResult;

public interface LoginUseCase {

    LoginResult login(LoginCommand command);
}
