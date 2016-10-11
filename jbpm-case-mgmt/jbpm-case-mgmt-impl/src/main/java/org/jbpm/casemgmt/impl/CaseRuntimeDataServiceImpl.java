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

package org.jbpm.casemgmt.impl;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.kie.internal.query.QueryParameterIdentifiers.FILTER;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jbpm.casemgmt.api.CaseNotFoundException;
import org.jbpm.casemgmt.api.CaseRuntimeDataService;
import org.jbpm.casemgmt.api.generator.CaseIdGenerator;
import org.jbpm.casemgmt.api.model.AdHocFragment;
import org.jbpm.casemgmt.api.model.CaseDefinition;
import org.jbpm.casemgmt.api.model.CaseMilestone;
import org.jbpm.casemgmt.api.model.CaseRole;
import org.jbpm.casemgmt.api.model.CaseStage;
import org.jbpm.casemgmt.api.model.instance.CaseInstance;
import org.jbpm.casemgmt.api.model.instance.CaseMilestoneInstance;
import org.jbpm.casemgmt.api.model.instance.CaseStageInstance;
import org.jbpm.casemgmt.impl.model.AdHocFragmentImpl;
import org.jbpm.casemgmt.impl.model.CaseDefinitionComparator;
import org.jbpm.casemgmt.impl.model.CaseDefinitionImpl;
import org.jbpm.casemgmt.impl.model.CaseMilestoneImpl;
import org.jbpm.casemgmt.impl.model.CaseRoleImpl;
import org.jbpm.casemgmt.impl.model.CaseStageImpl;
import org.jbpm.casemgmt.impl.model.instance.CaseMilestoneInstanceImpl;
import org.jbpm.casemgmt.impl.model.instance.CaseStageInstanceImpl;
import org.jbpm.kie.services.impl.model.NodeInstanceDesc;
import org.jbpm.kie.services.impl.model.ProcessAssetDesc;
import org.jbpm.kie.services.impl.security.DeploymentRolesManager;
import org.jbpm.runtime.manager.impl.AbstractRuntimeManager;
import org.jbpm.services.api.DeploymentEvent;
import org.jbpm.services.api.DeploymentEventListener;
import org.jbpm.services.api.RuntimeDataService;
import org.jbpm.services.api.model.DeployedAsset;
import org.jbpm.services.api.model.ProcessInstanceDesc;
import org.jbpm.shared.services.impl.QueryManager;
import org.jbpm.shared.services.impl.TransactionalCommandService;
import org.jbpm.shared.services.impl.commands.QueryNameCommand;
import org.jbpm.workflow.core.WorkflowProcess;
import org.jbpm.workflow.core.node.DynamicNode;
import org.jbpm.workflow.core.node.MilestoneNode;
import org.jbpm.workflow.core.node.StartNode;
import org.kie.api.KieBase;
import org.kie.api.definition.process.Node;
import org.kie.api.definition.process.NodeContainer;
import org.kie.api.definition.process.Process;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.internal.KieInternalServices;
import org.kie.internal.identity.IdentityProvider;
import org.kie.internal.process.CorrelationKey;
import org.kie.internal.process.CorrelationKeyFactory;
import org.kie.internal.query.QueryContext;


public class CaseRuntimeDataServiceImpl implements CaseRuntimeDataService, DeploymentEventListener {

    protected Set<CaseDefinitionImpl> availableCases = new HashSet<CaseDefinitionImpl>();

    private CorrelationKeyFactory correlationKeyFactory = KieInternalServices.Factory.get().newCorrelationKeyFactory();
    
    private CaseIdGenerator caseIdGenerator;
    
    private RuntimeDataService runtimeDataService;
    private TransactionalCommandService commandService;

    private IdentityProvider identityProvider;
    private DeploymentRolesManager deploymentRolesManager = new DeploymentRolesManager();
    
    // default statuses set to active only
    private List<Integer> statuses = Arrays.asList(ProcessInstance.STATE_ACTIVE);
    
    public CaseRuntimeDataServiceImpl() {
        QueryManager.get().addNamedQueries("META-INF/CaseMgmtorm.xml");
    }
   
    
    public CaseIdGenerator getCaseIdGenerator() {
        return caseIdGenerator;
    }
    
    public void setCaseIdGenerator(CaseIdGenerator caseIdGenerator) {
        this.caseIdGenerator = caseIdGenerator;
    }
    
    
    public void setRuntimeDataService(RuntimeDataService runtimeDataService) {
        this.runtimeDataService = runtimeDataService;
    }
    
    public void setCommandService(TransactionalCommandService commandService) {
        this.commandService = commandService;
    }
    
    public void setIdentityProvider(IdentityProvider identityProvider) {
        this.identityProvider = identityProvider;
    }
    
    public void setDeploymentRolesManager(DeploymentRolesManager deploymentRolesManager) {
        this.deploymentRolesManager = deploymentRolesManager;
    }

    /*
     * Deploy and undeploy handling
     */    
    @Override
    public void onDeploy(DeploymentEvent event) {
        AbstractRuntimeManager runtimeManager = (AbstractRuntimeManager) event.getDeployedUnit().getRuntimeManager();        
        KieBase kieBase = runtimeManager.getEnvironment().getKieBase();
        Collection<Process> processes = kieBase.getProcesses(); 
        
        Map<String, DeployedAsset> mapProcessById = event.getDeployedUnit().getDeployedAssets()
                                                                            .stream()
                                                                            .collect(toMap(DeployedAsset::getId, asset -> asset));        
        for( Process process : processes ) {
            if( ((WorkflowProcess)process).isDynamic()) {
                String caseIdPrefix = collectCaseIdPrefix(process);
                Collection<CaseMilestone> caseMilestones = collectMilestoness(process);
                Collection<CaseStage> caseStages = collectCaseStages(event.getDeploymentId(), process.getId(), ((WorkflowProcess)process));                
                Collection<CaseRole> caseRoles = collectCaseRoles(process);
                Collection<AdHocFragment> adHocFragments = collectAdHocFragments((WorkflowProcess)process);
                
                CaseDefinitionImpl caseDef = new CaseDefinitionImpl((ProcessAssetDesc) mapProcessById.get(process.getId()), caseIdPrefix, caseStages, caseMilestones, caseRoles, adHocFragments);
                
                availableCases.add(caseDef);
                caseIdGenerator.register(caseIdPrefix);
            }
        }
        
        // collect role information
        Collection<DeployedAsset> assets = event.getDeployedUnit().getDeployedAssets();
        List<String> roles = null;
        for( DeployedAsset asset : assets ) {
            if( asset instanceof ProcessAssetDesc ) {                
                if (roles == null) {
                    roles = ((ProcessAssetDesc) asset).getRoles();
                }
            }
        }
        if (roles == null) {
            roles = Collections.emptyList();
        }
        deploymentRolesManager.addRolesForDeployment(event.getDeploymentId(), roles);

    }

    @Override
    public void onUnDeploy(DeploymentEvent event) {
        
        Collection<CaseDefinitionImpl> undeployed = availableCases.stream()
                    .filter(caseDef -> caseDef.getDeploymentId().equals(event.getDeploymentId()))
                    .collect(toList());
        
        availableCases.removeAll(undeployed);
        
        undeployed.forEach(caseDef -> caseIdGenerator.unregister(caseDef.getIdentifierPrefix()));
        deploymentRolesManager.removeRolesForDeployment(event.getDeploymentId());

    }

    @Override
    public void onActivate(DeploymentEvent event) {
        // no op - all is done on RuntimeDataService level as CaseDefinition depends on ProcessDefinition
    }

    @Override
    public void onDeactivate(DeploymentEvent event) {
        // no op - all is done on RuntimeDataService level as CaseDefinition depends on ProcessDefinition
    }

    /*
     * CaseDefinition operations
     */
    

    @Override
    public CaseDefinition getCase(String deploymentId, String caseDefinitionId) {
        return availableCases.stream()
                            .filter(caseDef -> caseDef.getDeploymentId().equals(deploymentId) 
                                    && caseDef.getId().equals(caseDefinitionId))
                            .findFirst()
                            .orElse(null);        
    }
    
    @Override
    public Collection<CaseDefinition> getCases(QueryContext queryContext) {
        Collection<CaseDefinition> cases = availableCases.stream()
                .filter(caseDef -> caseDef.isActive())
                .sorted(new CaseDefinitionComparator(queryContext.getOrderBy(), queryContext.isAscending()))
                .skip(queryContext.getOffset())
                .limit(queryContext.getCount())
                .collect(toList());
        return cases;
    }

    @Override
    public Collection<CaseDefinition> getCases(String filter, QueryContext queryContext) {
        String pattern = "(?i)^.*"+filter+".*$";
        
        Collection<CaseDefinition> cases = availableCases.stream()
                .filter(caseDef -> caseDef.isActive() 
                        && (caseDef.getId().matches(pattern) || caseDef.getName().matches(pattern)))
                .sorted(new CaseDefinitionComparator(queryContext.getOrderBy(), queryContext.isAscending()))
                .skip(queryContext.getOffset())
                .limit(queryContext.getCount())
                .collect(toList());
        return cases;
    }

    @Override
    public Collection<CaseDefinition> getCasesByDeployment(String deploymentId, QueryContext queryContext) {
        Collection<CaseDefinition> cases = availableCases.stream()
                .filter(caseDef -> caseDef.isActive() && caseDef.getDeploymentId().equals(deploymentId))
                .sorted(new CaseDefinitionComparator(queryContext.getOrderBy(), queryContext.isAscending()))
                .skip(queryContext.getOffset())
                .limit(queryContext.getCount())
                .collect(toList());
        return cases;
    }
    
    
    /*
     * Case instance and its process instances operations
     */
    

    @Override
    public Collection<ProcessInstanceDesc> getProcessInstancesForCase(String caseId, QueryContext queryContext) {
        CorrelationKey correlationKey = correlationKeyFactory.newCorrelationKey(caseId);
        
        return runtimeDataService.getProcessInstancesByCorrelationKey(correlationKey, queryContext);        
    }

    
    @Override
    public Collection<ProcessInstanceDesc> getProcessInstancesForCase(String caseId, List<Integer> states, QueryContext queryContext) {
        CorrelationKey correlationKey = correlationKeyFactory.newCorrelationKey(caseId);
        
        return runtimeDataService.getProcessInstancesByCorrelationKeyAndStatus(correlationKey, states, queryContext);        
    } 
    

    @Override
    public Collection<CaseMilestoneInstance> getCaseInstanceMilestones(String caseId, boolean achievedOnly, QueryContext queryContext) {
        CorrelationKey correlationKey = correlationKeyFactory.newCorrelationKey(caseId);
        
        Collection<org.jbpm.services.api.model.NodeInstanceDesc> nodes = runtimeDataService.getNodeInstancesByCorrelationKeyNodeType(correlationKey, 
                                                                                                Arrays.asList(ProcessInstance.STATE_ACTIVE), 
                                                                                                Arrays.asList("MilestoneNode"), 
                                                                                                queryContext);
        
        Collection<Long> completedNodes = nodes.stream().filter(n -> ((NodeInstanceDesc)n).getType() == 1).map(n -> n.getId()).collect(toList());
        Predicate<org.jbpm.services.api.model.NodeInstanceDesc> filterNodes = null;
        if (achievedOnly) {            
            filterNodes = n -> ((NodeInstanceDesc)n).getType() == 1;             
        } else {
            filterNodes = n -> ((NodeInstanceDesc)n).getType() == 0;
        }
        Collection<CaseMilestoneInstance> milestones = nodes.stream()
        .filter(filterNodes)
        .map(n -> new CaseMilestoneInstanceImpl(String.valueOf(n.getId()), n.getName(), completedNodes.contains(n.getId()), n.getDataTimeStamp()))
        .collect(toList());
        
        return milestones;
    }

    @Override
    public Collection<CaseStageInstance> getCaseInstanceStages(String caseId, boolean activeOnly, QueryContext queryContext) {
        ProcessInstanceDesc pi = runtimeDataService.getProcessInstanceByCorrelationKey(correlationKeyFactory.newCorrelationKey(caseId));        
        if (pi == null || !pi.getState().equals(ProcessInstance.STATE_ACTIVE)) {
            throw new CaseNotFoundException("No case instance found with id " + caseId + " or it's not active anymore");
        }
        
        CaseDefinition caseDef = getCase(pi.getDeploymentId(), pi.getProcessId());
        Collection<CaseStageInstance> stages = internalGetCaseStages(caseDef, caseId, activeOnly, queryContext);
        
        return stages;
    }
    
    /*
     * Case instance queries
     */
    

    @Override
    public Collection<org.jbpm.services.api.model.NodeInstanceDesc> getActiveNodesForCase(String caseId, QueryContext queryContext) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("caseId", caseId);
        applyQueryContext(params, queryContext);
        applyDeploymentFilter(params);
        List<org.jbpm.services.api.model.NodeInstanceDesc> nodeInstances =  commandService.execute(new QueryNameCommand<List<org.jbpm.services.api.model.NodeInstanceDesc>>("getActiveNodesForCase", params));
        return nodeInstances;
    }


    @Override
    public Collection<CaseInstance> getCaseInstances(QueryContext queryContext) {

        return getCaseInstances(statuses, queryContext);
    }
    
    @Override
    public Collection<CaseInstance> getCaseInstances(List<Integer> statuses, QueryContext queryContext) {
        
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("statuses", statuses);
        applyQueryContext(params, queryContext);
        applyDeploymentFilter(params);
        List<CaseInstance> processInstances =  commandService.execute(new QueryNameCommand<List<CaseInstance>>("getCaseInstances", params));

        return processInstances;
    }


    @Override
    public Collection<CaseInstance> getCaseInstancesByDeployment(String deploymentId, List<Integer> statuses, QueryContext queryContext) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("deploymentId", deploymentId);
        params.put("statuses", statuses);
        applyQueryContext(params, queryContext);
        applyDeploymentFilter(params);
        List<CaseInstance> processInstances =  commandService.execute(new QueryNameCommand<List<CaseInstance>>("getCaseInstancesByDeployment", params));

        return processInstances;
    }


    @Override
    public Collection<CaseInstance> getCaseInstancesByDefinition(String definitionId, List<Integer> statuses, QueryContext queryContext) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("definitionId", definitionId);
        params.put("statuses", statuses);
        applyQueryContext(params, queryContext);
        applyDeploymentFilter(params);
        List<CaseInstance> processInstances =  commandService.execute(new QueryNameCommand<List<CaseInstance>>("getCaseInstancesByDefinition", params));

        return processInstances;
    }


    @Override
    public Collection<CaseInstance> getCaseInstancesOwnedBy(String owner, List<Integer> statuses, QueryContext queryContext) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("owner", owner);
        params.put("statuses", statuses);
        applyQueryContext(params, queryContext);
        applyDeploymentFilter(params);
        List<CaseInstance> processInstances =  commandService.execute(new QueryNameCommand<List<CaseInstance>>("getCaseInstancesOwnedBy", params));

        return processInstances;
    }
    
    @Override
    public Collection<AdHocFragment> getAdHocFragmentsForCase(String caseId) {
        ProcessInstanceDesc pi = runtimeDataService.getProcessInstanceByCorrelationKey(correlationKeyFactory.newCorrelationKey(caseId));        
        if (pi == null || !pi.getState().equals(ProcessInstance.STATE_ACTIVE)) {
            throw new CaseNotFoundException("No case instance found with id " + caseId + " or it's not active anymore");
        }
        
        CaseDefinition caseDef = getCase(pi.getDeploymentId(), pi.getProcessId());
        List<AdHocFragment> adHocFragments = new ArrayList<>();
        adHocFragments.addAll(caseDef.getAdHocFragments());
        
        Collection<CaseStageInstance> activeStages = internalGetCaseStages(caseDef, caseId, true, new QueryContext(0, 100));
        activeStages.forEach(stage -> adHocFragments.addAll(stage.getAdHocFragments()));
        
        return adHocFragments;
    }
    
    /*
     * Helper methods to parse process and extract case related information
     */
    
    public Collection<CaseStageInstance> internalGetCaseStages(CaseDefinition caseDef, String caseId, boolean activeOnly, QueryContext queryContext) {
        
        CorrelationKey correlationKey = correlationKeyFactory.newCorrelationKey(caseId);
        Collection<org.jbpm.services.api.model.NodeInstanceDesc> nodes = runtimeDataService.getNodeInstancesByCorrelationKeyNodeType(correlationKey, 
                                                                                            Arrays.asList(ProcessInstance.STATE_ACTIVE), 
                                                                                            Arrays.asList("DynamicNode"), 
                                                                                            queryContext);

        Map<String, CaseStage> stagesByName = caseDef.getCaseStages().stream()
        .collect(toMap(CaseStage::getId, c -> c)); 
        Predicate<org.jbpm.services.api.model.NodeInstanceDesc> filterNodes = null;
        if (activeOnly) {
            Collection<Long> completedNodes = nodes.stream().filter(n -> ((NodeInstanceDesc)n).getType() == 1).map(n -> n.getId()).collect(toList());
            filterNodes = n -> ((NodeInstanceDesc)n).getType() == 0 && !completedNodes.contains(((NodeInstanceDesc)n).getId());             
        } else {
            filterNodes = n -> ((NodeInstanceDesc)n).getType() == 0;
        }
        
        Collection<CaseStageInstance> stages = nodes.stream()
        .filter(filterNodes)
        .map(n -> new CaseStageInstanceImpl(n.getNodeId(), n.getName(), stagesByName.get(n.getNodeId()).getAdHocFragments(), Collections.emptyList()))
        .collect(toList());
        
        return stages;
    }
    
    private Collection<CaseRole> collectCaseRoles(Process process) {
        
        String roles = (String) process.getMetaData().get("customCaseRoles");
        if (roles == null) {
            return Collections.emptyList();
        }
        List<CaseRole> result = new ArrayList<CaseRole>();
        String[] roleStrings = roles.split(",");
        for (String roleString: roleStrings) {
            String[] elements = roleString.split(":");
            CaseRoleImpl role = new CaseRoleImpl(elements[0]);
            result.add(role);
            if (elements.length > 1) {
                role.setCardinality(Integer.parseInt(elements[1]));
            }
        }
        return result;
    }
    

    private String collectCaseIdPrefix(Process process) {
        String caseIdPrefix = (String) process.getMetaData().get("customCaseIdPrefix");
        if (caseIdPrefix == null) {
            return CaseDefinition.DEFAULT_PREFIX;
        }
        
        return caseIdPrefix;
    }

    private Collection<CaseMilestone> collectMilestoness(Process process) {
        Collection<CaseMilestone> result = new ArrayList<CaseMilestone>();
        getMilestones((WorkflowProcess) process, result);
        
        return result;
    }
    
    private void getMilestones(NodeContainer container, Collection<CaseMilestone> result) {
        for (Node node: container.getNodes()) {
            if (node instanceof MilestoneNode) {                
                result.add(new CaseMilestoneImpl((String) node.getMetaData().get("UniqueId"), node.getName(), ((MilestoneNode) node).getConstraint(), false));               
            }
            if (node instanceof NodeContainer) {
                getMilestones((NodeContainer) node, result);
            }
        }
    }
    
    private Collection<CaseStage> collectCaseStages(String deploymentId, String processId, NodeContainer process) {
        Collection<CaseStage> result = new ArrayList<CaseStage>();
        
        for (Node node : process.getNodes()) {
            if (node instanceof DynamicNode) {
                DynamicNode dynamicNode = (DynamicNode) node;
                Collection<AdHocFragment> adHocFragments = collectAdHocFragments(dynamicNode);
                
                result.add(new CaseStageImpl((String)((DynamicNode) node).getMetaData("UniqueId"), node.getName(), adHocFragments));
            }
        }
        return result;
    }


    private Collection<AdHocFragment> collectAdHocFragments(NodeContainer process) {
        List<AdHocFragment> result = new ArrayList<AdHocFragment>();
        
        checkAdHoc(process, result);
        
        return result;
    }
    
    private void checkAdHoc(NodeContainer nodeContainer, List<AdHocFragment> result) {
        for (Node node : nodeContainer.getNodes()) {
            if (node instanceof StartNode) {
                continue;
            }
            if (node.getIncomingConnections().isEmpty()) {
                result.add(new AdHocFragmentImpl(node.getName(), node.getClass().getSimpleName()));
            }
        }
    }
    
    protected void applyQueryContext(Map<String, Object> params, QueryContext queryContext) {
        if (queryContext != null) {
            params.put("firstResult", queryContext.getOffset());
            params.put("maxResults", queryContext.getCount());

            if (queryContext.getOrderBy() != null && !queryContext.getOrderBy().isEmpty()) {
                params.put(QueryManager.ORDER_BY_KEY, queryContext.getOrderBy());

                if (queryContext.isAscending()) {
                    params.put(QueryManager.ASCENDING_KEY, "true");
                } else {
                    params.put(QueryManager.DESCENDING_KEY, "true");
                }
            }
        }
    }

    protected void applyDeploymentFilter(Map<String, Object> params) {
        if (deploymentRolesManager != null) {
            List<String> deploymentIdForUser = deploymentRolesManager.getDeploymentsForUser(identityProvider);
    
            if (deploymentIdForUser != null && !deploymentIdForUser.isEmpty()) {
                params.put(FILTER, " log.externalId in (:deployments) ");
                params.put("deployments", deploymentIdForUser);
            }
        }
    }

}
