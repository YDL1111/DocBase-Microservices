package com.docbase.iam.security;

import com.docbase.iam.user.domain.SysUser;
import com.docbase.iam.user.mapper.SysUserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Loads user identity during username/password login. This is only used for the
 * initial login flow; subsequent requests are authenticated via JWT.
 */
@Service
public class IamUserDetailsService implements UserDetailsService {

    private final SysUserMapper userMapper;

    public IamUserDetailsService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SysUser>()
                        .eq("username", username)
                        .last("limit 1"));
        if (user == null) {
            throw new UsernameNotFoundException("invalid username or password");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new UsernameNotFoundException("account is disabled");
        }
        return new IamUserPrincipal(
                user.getUserId(),
                user.getUsername(),
                user.getIsAdmin() != null && user.getIsAdmin() == 1,
                Collections.emptySet());
    }
}
