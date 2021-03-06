/*
 * ====================================================================
 * Copyright (c) 2004-2008 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.core.internal.server.dav.handlers;

import java.util.LinkedList;

import org.tmatesoft.svn.core.internal.server.dav.DAVException;
import org.tmatesoft.svn.core.internal.server.dav.DAVLock;
import org.tmatesoft.svn.core.internal.server.dav.DAVLockScope;
import org.tmatesoft.svn.core.internal.server.dav.DAVResource;


/**
 * @version 1.2.0
 * @author  TMate Software Ltd.
 */
public class DAVInheritWalker implements IDAVResourceWalkHandler {

    private boolean myIsSkipRoot;
    private DAVResource myResource;
    private DAVLock myLock;
    
    public DAVInheritWalker(DAVResource resource, DAVLock lock, boolean skipRoot) {
        myResource = resource;
        myIsSkipRoot = skipRoot;
        myLock = lock;
    }
    
    public DAVResponse handleResource(DAVResponse response, DAVResource resource, DAVLockInfoProvider lockInfoProvider, LinkedList ifHeaders, 
            int flags, DAVLockScope lockScope, CallType callType)
            throws DAVException {
        if (myIsSkipRoot && myResource.equals(resource)) {
            return null;
        }
        
        lockInfoProvider.appendLock(resource, myLock);
        return null;
    }

}
