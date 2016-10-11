/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jbpm.casemgmt.impl.model.instance;

import static java.util.stream.Collectors.toMap;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jbpm.casemgmt.api.model.CaseRole;
import org.jbpm.casemgmt.api.model.instance.CaseFileDataFilter;
import org.jbpm.casemgmt.api.model.instance.CaseFileInstance;
import org.jbpm.casemgmt.api.model.instance.CaseRoleInstance;
import org.jbpm.casemgmt.api.model.instance.CommentInstance;
import org.kie.api.runtime.process.CaseAssignment;
import org.kie.api.task.model.OrganizationalEntity;

/*
 * Implementation note: since the CaseFileInstanceImpl will be marshalled/unmarshalled by 
 * org.jbpm.casemgmt.impl.marshalling.CaseFileInstanceMarshallingStrategy then make sure whenever
 * extending this class with new fields they are properly implemented in marshaller strategy as well.
 */
public class CaseFileInstanceImpl implements CaseFileInstance, CaseAssignment, Serializable {

    private static final long serialVersionUID = -4161603356119857561L;
    
    private String separator = System.getProperty("org.jbpm.ht.user.separator", ",");

    private String caseId;
    private Date caseStartDate;
    private Date caseEndDate;
    private Date caseReopenDate;
    
    private Map<String, Object> data = new HashMap<>();    
    private Map<String, CaseRoleInstance> roles = new HashMap<>();    
    private List<CommentInstance> comments = new ArrayList<>();
    
    public CaseFileInstanceImpl() {
        
    }
    
    public CaseFileInstanceImpl(String caseId) {
        this.caseId = caseId;
        this.caseStartDate = new Date();
    }
    
    public CaseFileInstanceImpl(Map<String, Object> data) {        
        this.data = data;
        this.caseStartDate = new Date();
    }
    
    public CaseFileInstanceImpl(String caseId, Map<String, Object> data) {
        this.caseId = caseId;
        this.data = data;
        this.caseStartDate = new Date();
    }

    @Override
    public String getCaseId() {
        return caseId;
    }
    
    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    @Override
    public Date getCaseStartDate() {
        return caseStartDate;
    }

    @Override
    public Date getCaseEndDate() {
        return caseEndDate;
    }

    @Override
    public Map<String, Object> getData() {
        return this.data;
    }

    @Override
    public Map<String, Object> getData(CaseFileDataFilter filter) {
        this.data.entrySet()
        .stream()
        .filter(p -> filter.accept(p.getKey(), p.getValue()))
        .collect(toMap(p -> p.getKey(), p -> p.getValue()));
        return null;
    }

    @Override
    public void addAll(Map<String, Object> data) {
        this.data.putAll(data);
    }

    @Override
    public void add(String name, Object data) {
        this.data.put(name, data);
    }

    @Override
    public void remove(String name) {
        this.data.remove(name);
    }

    @Override
    public void remove(CaseFileDataFilter filter) {
        Map<String, Object> collect = getData(filter);
        collect.keySet().forEach( key -> this.data.remove(key));
    }

    @Override
    public void removeAll() {
        this.data.clear();
    }

    @Override
    public Object getData(String name) {
        return this.data.get(name);
    }
    
    public void setCaseEndDate(Date caseEndDate) {
        this.caseEndDate = caseEndDate;
    }

    @Override
    public Date getCaseReopenDate() {
        return caseReopenDate;
    }

    public void setCaseReopenDate(Date caseReopenDate) {
        this.caseReopenDate = caseReopenDate;
    }

    @Override
    public void assign(String roleName, OrganizationalEntity entity) {
        CaseRoleInstance caseRoleInstance = this.roles.get(roleName);
        if (caseRoleInstance == null) {
            throw new IllegalArgumentException("No role with name " + roleName + " was found");
        }
        ((CaseRoleInstanceImpl)caseRoleInstance).addRoleAssignment(entity);        
    }

    @Override
    public void remove(String roleName, OrganizationalEntity entity) {
        CaseRoleInstance caseRoleInstance = this.roles.get(roleName);
        if (caseRoleInstance != null) {
            ((CaseRoleInstanceImpl)caseRoleInstance).removeRoleAssignment(entity);
        }      
    }
    
    @Override
    public Collection<OrganizationalEntity> getAssignments(String roleName) {
        String[] roleNames = roleName.split(separator);
        Collection<OrganizationalEntity> foundAssignments = new ArrayList<>();
        for (String role : roleNames) {
            CaseRoleInstance caseRoleInstance = this.roles.get(role);
            if (caseRoleInstance == null) {
                throw new IllegalArgumentException("No role with name " + role + " was found");
            }
            foundAssignments.addAll(caseRoleInstance.getRoleAssignments());
        }
        return foundAssignments;
    }
    
    public Collection<CaseRoleInstance> getAssignments() {
        return Collections.unmodifiableCollection(roles.values());
    }
    
    public void setupRoles(Collection<CaseRole> roles) {
        if (roles != null) {
            roles.stream().forEach(r -> this.roles.put(r.getName(), new CaseRoleInstanceImpl(r.getName(), r.getCardinality())));
        }
    }
    
    public Map<String, CaseRoleInstance> getRolesAssignments() {
        return roles;
    }
    
    public void setRolesAssignments(Map<String, CaseRoleInstance> roles) {
        this.roles = roles;
    }
    
    public Collection<CommentInstance> getComments() {
        return Collections.unmodifiableCollection(comments);
    }

    public void addComment(CommentInstance comment) {
        this.comments.add(comment);
        
    }

    public void removeComment(CommentInstance comment) {
        this.comments.remove(comment);
        
    }
    
    public void setCaseStartDate(Date caseStartDate) {
        this.caseStartDate = caseStartDate;
    }

    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    
    public void setComments(List<CommentInstance> comments) {
        this.comments = comments;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((caseEndDate == null) ? 0 : caseEndDate.hashCode());
        result = prime * result + ((caseId == null) ? 0 : caseId.hashCode());
        result = prime * result + ((caseStartDate == null) ? 0 : caseStartDate.hashCode());
        result = prime * result + ((caseReopenDate == null) ? 0 : caseReopenDate.hashCode());
        result = prime * result + ((data == null) ? 0 : data.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        CaseFileInstanceImpl other = (CaseFileInstanceImpl) obj;
        if (caseEndDate == null) {
            if (other.caseEndDate != null)
                return false;
        } else if (!caseEndDate.equals(other.caseEndDate))
            return false;
        if (caseId == null) {
            if (other.caseId != null)
                return false;
        } else if (!caseId.equals(other.caseId))
            return false;
        if (caseStartDate == null) {
            if (other.caseStartDate != null)
                return false;
        } else if (!caseStartDate.equals(other.caseStartDate))
            return false;
        if (caseReopenDate == null) {
            if (other.caseReopenDate != null)
                return false;
        } else if (!caseReopenDate.equals(other.caseReopenDate))
            return false;
        if (data == null) {
            if (other.data != null)
                return false;
        } else if (!data.equals(other.data))
            return false;
        return true;
    }

}
