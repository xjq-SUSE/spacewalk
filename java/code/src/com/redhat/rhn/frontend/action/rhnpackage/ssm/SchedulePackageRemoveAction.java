/**
 * Copyright (c) 2009 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.frontend.action.rhnpackage.ssm;

import com.redhat.rhn.common.db.datasource.DataResult;
import com.redhat.rhn.common.localization.LocalizationService;
import com.redhat.rhn.common.util.DatePicker;
import com.redhat.rhn.domain.rhnset.RhnSet;
import com.redhat.rhn.domain.rhnset.SetCleanup;
import com.redhat.rhn.domain.server.Server;
import com.redhat.rhn.domain.user.User;
import com.redhat.rhn.frontend.dto.PackageListItem;
import com.redhat.rhn.frontend.struts.RequestContext;
import com.redhat.rhn.frontend.struts.RhnHelper;
import com.redhat.rhn.frontend.struts.RhnListAction;
import com.redhat.rhn.frontend.struts.SessionSetHelper;
import com.redhat.rhn.frontend.struts.StrutsDelegate;
import com.redhat.rhn.frontend.taglibs.list.TagHelper;
import com.redhat.rhn.frontend.taglibs.list.helper.ListHelper;
import com.redhat.rhn.frontend.taglibs.list.helper.Listable;
import com.redhat.rhn.manager.action.ActionManager;
import com.redhat.rhn.manager.rhnset.RhnSetDecl;
import com.redhat.rhn.manager.rhnset.RhnSetManager;
import com.redhat.rhn.manager.system.SystemManager;

import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.apache.struts.action.ActionMessage;
import org.apache.struts.action.ActionMessages;
import org.apache.struts.action.DynaActionForm;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Handles the display and capture of scheduling package removals for systems in the SSM.
 *
 * @version $Revision$
 */
public class SchedulePackageRemoveAction extends RhnListAction implements Listable {

    private static final String DATA_SET = "pageList";

    /** {@inheritDoc} */
    public ActionForward execute(ActionMapping actionMapping,
                                 ActionForm actionForm,
                                 HttpServletRequest request,
                                 HttpServletResponse response) throws Exception {

        RequestContext requestContext = new RequestContext(request);

        ListHelper helper = new ListHelper(this, request);
        helper.setDataSetName(DATA_SET);
        helper.execute();

        if (request.getParameter("dispatch") != null) {
            if (requestContext.wasDispatched("installconfirm.jsp.confirm")) {
                return executePackageAction(actionMapping, actionForm, request, response);
            }
        }

        // Prepopulate the date picker
        DynaActionForm dynaForm = (DynaActionForm) actionForm;
        DatePicker picker = getStrutsDelegate().prepopulateDatePicker(request, dynaForm,
            "date", DatePicker.YEAR_RANGE_POSITIVE);

        request.setAttribute("date", picker);

        return actionMapping.findForward(RhnHelper.DEFAULT_FORWARD);

    }

    /** {@inheritDoc} */
    public List getResult(RequestContext context) {

        HttpServletRequest request = context.getRequest();
        User user = context.getLoggedInUser();

        // Stuff packages into an RhnSet to be used in the query
        String packagesDecl = (String) request.getAttribute("packagesDecl");
        if (packagesDecl != null) {
            Set<String> data = SessionSetHelper.lookupAndBind(request, packagesDecl);
    
            RhnSet packageSet = RhnSetManager.createSet(user.getId(),
                RhnSetDecl.SSM_REMOVE_PACKAGES_LIST.getLabel(), SetCleanup.NOOP);
    
            for (String idCombo : data) {
                PackageListItem item = PackageListItem.parse(idCombo);
                packageSet.addElement(item.getIdOne(), item.getIdTwo(), item.getIdThree());
            }
    
            RhnSetManager.store(packageSet);
        }

        DataResult results = SystemManager.ssmSystemPackagesToRemove(user,
            RhnSetDecl.SSM_REMOVE_PACKAGES_LIST.getLabel());

        TagHelper.bindElaboratorTo("groupList", results.getElaborator(), request);

        return results;
    }

    /**
     * Creates the package removal action.
     *
     * @param mapping  struts mapping
     * @param formIn   struts form
     * @param request  HTTP request
     * @param response HTTP response
     * @return
     */
    private ActionForward executePackageAction(ActionMapping mapping,
                                               ActionForm formIn,
                                               HttpServletRequest request,
                                               HttpServletResponse response) {

        RequestContext context = new RequestContext(request);
        StrutsDelegate strutsDelegate = getStrutsDelegate();
        User user = context.getLoggedInUser();

        // Load the date selected by the user
        Date earliest = getStrutsDelegate().readDatePicker((DynaActionForm) formIn,
            "date", DatePicker.YEAR_RANGE_POSITIVE);

        // Parse through all of the results        
        DataResult result = (DataResult) getResult(context); 
        result.elaborate();
        
        int numPackages = 0;
                                       
        /* 443500 - The following was changed to be able to stuff all of the package
           removals into a single action. The schedule package removal page will display
           a fine grained mapping of server to package removed (taking into account to
           only show packages that exist on the server).
           
           However, there is no issue in requesting a client delete a package it doesn't
           have. So when we create the action, populate it with all packages and for
           every server to which any package removal applies. This will let us keep all
           of the removals coupled under a single scheduled action and won't cause an
           issue on the client when the scheduled removals are picked up.
        
           jdobies, Apr 8, 2009
         */
        
        // The package collection is a set to prevent duplciates when keeping a running
        // total of all packages selected
        Set<PackageListItem> allPackages = new HashSet<PackageListItem>();
        List<Server> allServers = new ArrayList<Server>(result.size());
        
        for (Iterator it = result.iterator(); it.hasNext();) {
        
            // Add action for each package found in the elaborator
            Map data = (Map) it.next();
            
            // Load the server
            Long sid = (Long)data.get("id");              
            Server server = SystemManager.lookupByIdAndUser(sid, user);
            allServers.add(server);
            
            // Get the packages out of the elaborator
            List elabList = (List) data.get("elaborator0");
            numPackages += elabList.size();
            
            for (Iterator elabIt = elabList.iterator(); elabIt.hasNext();) {
                Map elabData = (Map) elabIt.next();
                String idCombo = (String) elabData.get("id_combo");
                PackageListItem item = PackageListItem.parse(idCombo);
                allPackages.add(item);
            }
        }

        // Convert to list of maps
        List<PackageListItem> allPackagesList = new ArrayList<PackageListItem>(allPackages);
        List<Map<String, Long>> packageListData =
            PackageListItem.toKeyMaps(allPackagesList);
            
        // Create the action
        ActionManager.schedulePackageRemoval(user, allServers, packageListData, earliest);
        
        // Remove the packages from session and the DB
        SessionSetHelper.obliterate(request, request.getParameter("packagesDecl"));

        RhnSetManager.deleteByLabel(user.getId(),
            RhnSetDecl.SSM_REMOVE_PACKAGES_LIST.getLabel());

        ActionMessages msgs = new ActionMessages();

        // Check to determine to display single or plural confirmation message
        LocalizationService l10n = LocalizationService.getInstance();
        if (numPackages == 1) {
            msgs.add(ActionMessages.GLOBAL_MESSAGE,
                new ActionMessage("ssm.package.remove.message.packageremoval",
                                  l10n.formatNumber(numPackages)));
        }
        else {
            msgs.add(ActionMessages.GLOBAL_MESSAGE,
                new ActionMessage("ssm.package.remove.message.packageremovals",
                                  l10n.formatNumber(numPackages)));
        }
        strutsDelegate.saveMessages(request, msgs);

        return mapping.findForward("confirm");
    }

}

