package com.mobilegroup20.modelpilot.data.repository;

/** Implemented by the team's account module; read live session values on every request. */
public interface SessionProvider {
    String token();
    String accountId();
    SessionProvider SIGNED_OUT = new SessionProvider() {
        public String token() { return null; }
        public String accountId() { return null; }
    };
}
