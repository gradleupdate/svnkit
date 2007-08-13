/*
 * ====================================================================
 * Copyright (c) 2004-2007 TMate Software Ltd.  All rights reserved.
 *
 * This software is licensed as described in the file COPYING, which
 * you should have received as part of this distribution.  The terms
 * are also available at http://svnkit.com/license.html.
 * If newer versions of this license are posted there, you may use a
 * newer version instead, at your option.
 * ====================================================================
 */
package org.tmatesoft.svn.cli2.command;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.svn.cli2.SVNCommand;
import org.tmatesoft.svn.cli2.SVNCommandTarget;
import org.tmatesoft.svn.cli2.SVNOption;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.wc.SVNWCClient;


/**
 * @version 1.1.2
 * @author  TMate Software Ltd.
 */
public class SVNCatCommand extends SVNCommand {

    public SVNCatCommand() {
        super("cat", null);
    }

    protected Collection createSupportedOptions() {
        Collection options = new LinkedList();
        options.add(SVNOption.REVISION);
        options = SVNOption.addAuthOptions(options);
        options.add(SVNOption.CONFIG_DIR);
        return options;
    }

    public void run() throws SVNException {
        List targets = getEnvironment().combineTargets(null);
        if (targets.isEmpty()) {
            SVNErrorManager.error(SVNErrorMessage.create(SVNErrorCode.CL_INSUFFICIENT_ARGS));
        }
        SVNWCClient client = getEnvironment().getClientManager().getWCClient();

        for(int i = 0; i < targets.size(); i++) {
            SVNCommandTarget target = new SVNCommandTarget((String) targets.get(i), true);
            try {
                if (target.isURL()) {
                    client.doGetFileContents(target.getURL(), target.getPegRevision(), getEnvironment().getStartRevision(), true, getEnvironment().getOut());
                } else {
                    client.doGetFileContents(target.getFile(), target.getPegRevision(), getEnvironment().getStartRevision(), true, getEnvironment().getOut());
                }
            } catch (SVNException e) {
                SVNErrorMessage err = e.getErrorMessage();
                getEnvironment().handleWarning(err, new SVNErrorCode[] {SVNErrorCode.UNVERSIONED_RESOURCE, SVNErrorCode.ENTRY_NOT_FOUND, SVNErrorCode.CLIENT_IS_DIRECTORY});
            }
        }
    }

}
