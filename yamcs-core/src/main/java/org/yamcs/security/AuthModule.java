package org.yamcs.security;

import org.yamcs.InitException;
import org.yamcs.Spec;
import org.yamcs.YConfiguration;
import org.yamcs.http.auth.AuthHandler;

/**
 * Interface implemented by the Authentication and Authorization modules.
 * 
 * The AuthModule has to associate to each user AuthenticationInfo that may contain contextual security properties.
 * Based on this {@link AuthHandler} will generate a JWT token which is passed between the client and the server with
 * each request.
 * 
 * @author nm
 */
public interface AuthModule {

    /**
     * Returns the valid configuration of the input args of this AuthModule.
     * 
     * @return the argument specification.
     */
    Spec getSpec();

    /**
     * Initialize this AuthModule.
     * 
     * @param args
     *            The configured arguments for this AuthModule. If {@link #getSpec()} is implemented then this contains
     *            the arguments after being validated (including any defaults).
     * @throws InitException
     *             When something goes wrong during the execution of this method.
     */
    void init(YConfiguration args) throws InitException;

    /**
     * Identify the subject based on the given information.
     * 
     * @param token
     * @return an info object containing the principal of the subject, or <tt>null</tt> if the login failed
     */
    AuthenticationInfo getAuthenticationInfo(AuthenticationToken token) throws AuthenticationException;

    /**
     * Hook that is called on all AuthModules when a login attempt was successful.
     * <p>
     * An example use case is an LDAP AuthModule that queries for additional user information after a successful
     * Kerberos login.
     * <p>
     * The default implementation does nothing.
     */
    default void authenticationSucceeded(AuthenticationInfo authenticationInfo) {
    }

    /**
     * Retrieve access control information based on the given AuthenticationInfo. This AuthenticationInfo may have been
     * generated by a different AuthModule.
     * 
     * @return an info object containing role/privilege information of the subject
     */
    AuthorizationInfo getAuthorizationInfo(AuthenticationInfo authenticationInfo) throws AuthorizationException;

    /**
     * Verify if previously generated authentication info is (still) valid. For example, if the authentication info
     * references an externally issued expiring ticket, this can be validated here.
     * <p>
     * This method is called very frequently, so implementations must take care to limit external requests.
     * 
     * @param authenticationInfo
     *            information relevant to the authentication process
     * 
     * @return true if the authentication info is valid, false otherwise
     * 
     */
    boolean verifyValidity(AuthenticationInfo authenticationInfo);
}
