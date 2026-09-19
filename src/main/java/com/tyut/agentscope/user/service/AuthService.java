package com.tyut.agentscope.user.service;

import com.tyut.agentscope.user.model.LoginDTO;
import com.tyut.agentscope.user.model.LoginUserVO;
import com.tyut.agentscope.user.model.RegisterDTO;

public interface AuthService {

    LoginUserVO register(RegisterDTO dto);

    LoginUserVO login(LoginDTO dto);

    void logout();

    LoginUserVO currentUser();

    String currentUserId();
}
