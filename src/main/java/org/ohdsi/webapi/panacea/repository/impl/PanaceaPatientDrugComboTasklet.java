/**
 * The contents of this file are subject to the Regenstrief Public License
 * Version 1.0 (the "License"); you may not use this file except in compliance with the License.
 * Please contact Regenstrief Institute if you would like to obtain a copy of the license.
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) Regenstrief Institute.  All Rights Reserved.
 */
package org.ohdsi.webapi.panacea.repository.impl;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.Days;
import org.ohdsi.sql.SqlRender;
import org.ohdsi.sql.SqlTranslate;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 *
 */
public class PanaceaPatientDrugComboTasklet implements Tasklet {
    
    private static final Log log = LogFactory.getLog(PanaceaPatientDrugComboTasklet.class);
    
    private static final int String = 0;
    
    private static final int List = 0;
    
    private JdbcTemplate jdbcTemplate;
    
    private TransactionTemplate transactionTemplate;
    
    private final Comparator patientStageCombinationCountDateComparator = new Comparator<PatientStageCombinationCount>() {
        
        @Override
        public int compare(final PatientStageCombinationCount pscc1, final PatientStageCombinationCount pscc2) {
            if ((pscc1 != null) && (pscc2 != null) && (pscc1.getStartDate() != null) && (pscc2.getStartDate() != null)) {
                return pscc1.getStartDate().before(pscc2.getStartDate()) ? -1 : 1;
            }
            return 0;
        }
    };
    
    /**
     * @param jdbcTemplate
     * @param transactionTemplate
     * @param pncService
     * @param pncStudy
     */
    public PanaceaPatientDrugComboTasklet(final JdbcTemplate jdbcTemplate, final TransactionTemplate transactionTemplate) {
        super();
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }
    
    /**
     * @see org.springframework.batch.core.step.tasklet.Tasklet#execute(org.springframework.batch.core.StepContribution,
     *      org.springframework.batch.core.scope.context.ChunkContext)
     */
    @Override
    public RepeatStatus execute(final StepContribution contribution, final ChunkContext chunkContext) throws Exception {
        try {
            final Map<String, Object> jobParams = chunkContext.getStepContext().getJobParameters();
            
            final String sql = this.getSql(jobParams, chunkContext);
            
            log.debug("PanaceaPatientDrugComboTasklet.execute, begin... ");
            
            final List<PatientStageCount> patientStageCountList = this.jdbcTemplate.query(sql,
                new RowMapper<PatientStageCount>() {
                    
                    @Override
                    public PatientStageCount mapRow(final ResultSet rs, final int rowNum) throws SQLException {
                        final PatientStageCount patientStageCount = new PatientStageCount();
                        
                        patientStageCount.setPersonId(rs.getLong("person_id"));
                        patientStageCount.setCmbId(rs.getLong("cmb_id"));
                        patientStageCount.setStartDate(rs.getDate("start_date"));
                        patientStageCount.setEndDate(rs.getDate("end_date"));
                        
                        return patientStageCount;
                    }
                });
            
            log.debug("PanaceaPatientDrugComboTasklet.execute, returned size -- " + patientStageCountList.size());
            
            final List<PatientStageCombinationCount> calculatedOverlappingPSCCList = mergeComboOverlapWindow(
                patientStageCountList, 30);
            
            if (calculatedOverlappingPSCCList != null) {
                calculatedOverlappingPSCCList.toString();
            }
            
            return RepeatStatus.FINISHED;
        } catch (final Exception e) {
            e.printStackTrace();
            
            //TODO -- consider this bad? and terminate the job?
            //return RepeatStatus.CONTINUABLE;
            return RepeatStatus.FINISHED;
        } finally {
            //TODO
            final DefaultTransactionDefinition completeTx = new DefaultTransactionDefinition();
            completeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            final TransactionStatus completeStatus = this.transactionTemplate.getTransactionManager().getTransaction(
                completeTx);
            this.transactionTemplate.getTransactionManager().commit(completeStatus);
        }
        
    }
    
    /**
     * @return the jdbcTemplate
     */
    public JdbcTemplate getJdbcTemplate() {
        return this.jdbcTemplate;
    }
    
    /**
     * @param jdbcTemplate the jdbcTemplate to set
     */
    public void setJdbcTemplate(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * @return the transactionTemplate
     */
    public TransactionTemplate getTransactionTemplate() {
        return this.transactionTemplate;
    }
    
    /**
     * @param transactionTemplate the transactionTemplate to set
     */
    public void setTransactionTemplate(final TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }
    
    private String getSql(final Map<String, Object> jobParams, final ChunkContext chunkContext) {
        //String sql = ResourceHelper.GetResourceAsString("/resources/panacea/sql/getPersonIds.sql");
        String sql = "select ptstg.person_id as person_id, ptstg.tx_stg_cmb_id cmb_id, ptstg.stg_start_date start_date, ptstg.stg_end_date end_date "
                + "from HN31JHWQ_PNC_PTSTG_CT  ptstg "
                + "where "
                + "person_id in (@allDistinctPersonId) "
                + "order by person_id, stg_start_date, stg_end_date";
        
        final String cdmTableQualifier = (String) jobParams.get("cdm_schema");
        final String resultsTableQualifier = (String) jobParams.get("ohdsi_schema");
        final String cohortDefId = (String) jobParams.get("cohortDefId");
        final String drugConceptId = (String) jobParams.get("drugConceptId");
        final String sourceDialect = (String) jobParams.get("sourceDialect");
        final String sourceId = (String) jobParams.get("sourceId");
        final List<String> allDistinctPersonId = (List<String>) chunkContext.getStepContext().getJobExecutionContext()
                .get("allDistinctPersonId");
        String allDistinctPersonIdStr = "";
        if (allDistinctPersonId != null) {
            boolean firstId = true;
            for (final String ids : allDistinctPersonId) {
                allDistinctPersonIdStr = firstId ? allDistinctPersonIdStr.concat(ids) : allDistinctPersonIdStr.concat(","
                        + ids);
                firstId = false;
            }
        }
        
        final String[] params = new String[] { "cdm_schema", "ohdsi_schema", "cohortDefId", "drugConceptId", "sourceId",
                "allDistinctPersonId" };
        final String[] values = new String[] { cdmTableQualifier, resultsTableQualifier, cohortDefId, drugConceptId,
                sourceId, allDistinctPersonIdStr };
        
        sql = SqlRender.renderSql(sql, params, values);
        sql = SqlTranslate.translateSql(sql, "sql server", sourceDialect, null, resultsTableQualifier);
        
        return sql;
    }
    
    private List<PatientStageCombinationCount> mergeComboOverlapWindow(final List<PatientStageCount> patientStageCountList,
                                                                       final int switchWindow) {
        if ((patientStageCountList != null) && (patientStageCountList.size() > 0)) {
            final Map<Long, List<PatientStageCombinationCount>> mergedComboPatientMap = new HashMap<Long, List<PatientStageCombinationCount>>();
            
            Long currentPersonId = patientStageCountList.get(0).getPersonId();
            final List<PatientStageCombinationCount> mergedList = new ArrayList<PatientStageCombinationCount>();
            final List<PatientStageCombinationCount> truncatedList = new ArrayList<PatientStageCombinationCount>();
            for (final PatientStageCount psc : patientStageCountList) {
                if (psc.getPersonId().equals(currentPersonId)) {
                    //from same patient
                    while ((truncatedList.size() > 0) && truncatedList.get(0).getStartDate().before(psc.getStartDate())) {
                        popAndMergeList(mergedList, truncatedList, null, switchWindow);
                    }
                    
                    final PatientStageCombinationCount newPSCC = new PatientStageCombinationCount();
                    newPSCC.setPersonId(psc.getPersonId());
                    newPSCC.setComboIds(psc.getCmbId().toString());
                    newPSCC.setStartDate(psc.getStartDate());
                    newPSCC.setEndDate(psc.getEndDate());
                    
                    popAndMergeList(mergedList, truncatedList, newPSCC, switchWindow);
                    
                    if (patientStageCountList.indexOf(psc) == (patientStageCountList.size() - 1)) {
                        //last object in the original list
                        
                        while (truncatedList.size() > 0) {
                            popAndMergeList(mergedList, truncatedList, null, switchWindow);
                        }
                        
                        final List<PatientStageCombinationCount> currentPersonIdMergedList = new ArrayList<PatientStageCombinationCount>();
                        currentPersonIdMergedList.addAll(mergedList);
                        mergedComboPatientMap.put(currentPersonId, currentPersonIdMergedList);
                        
                        mergedList.clear();
                        truncatedList.clear();
                    }
                } else {
                    //read to roll to next patient after popping all from truncatedList 
                    while (truncatedList.size() > 0) {
                        popAndMergeList(mergedList, truncatedList, null, switchWindow);
                    }
                    
                    final List<PatientStageCombinationCount> currentPersonIdMergedList = new ArrayList<PatientStageCombinationCount>();
                    currentPersonIdMergedList.addAll(mergedList);
                    mergedComboPatientMap.put(currentPersonId, currentPersonIdMergedList);
                    
                    mergedList.clear();
                    truncatedList.clear();
                    
                    //first object for next patient
                    currentPersonId = psc.getPersonId();
                    
                    final PatientStageCombinationCount newPSCC = new PatientStageCombinationCount();
                    newPSCC.setPersonId(psc.getPersonId());
                    newPSCC.setComboIds(psc.getCmbId().toString());
                    newPSCC.setStartDate(psc.getStartDate());
                    newPSCC.setEndDate(psc.getEndDate());
                    
                    popAndMergeList(mergedList, truncatedList, newPSCC, switchWindow);
                }
            }
            
            final List<PatientStageCombinationCount> returnPSCCList = new ArrayList(mergedComboPatientMap.values());
            
            return returnPSCCList;
            
        } else {
            //TODO - error logging
            return null;
        }
    }
    
    private void popAndMergeList(final List<PatientStageCombinationCount> mergedList,
                                 final List<PatientStageCombinationCount> truncatedList,
                                 final PatientStageCombinationCount newConstructedPSCC, final int switchWindow) {
        if ((mergedList != null) && (truncatedList != null)) {
            PatientStageCombinationCount poppingPSCC = null;
            boolean newPSCCFromOriginalList = false;
            if (newConstructedPSCC == null) {
                poppingPSCC = truncatedList.get(0);
            } else {
                poppingPSCC = newConstructedPSCC;
                newPSCCFromOriginalList = true;
            }
            
            if (mergedList.size() > 0) {
                //mergedList has elements
                final PatientStageCombinationCount lastMergedPSCC = mergedList.get(mergedList.size() - 1);
                
                if (lastMergedPSCC.getStartDate().after(poppingPSCC.getStartDate())) {
                    
                    log.error("Error in popAndMergeList -- starting date wrong in popAndMergeList");
                }
                
                if (poppingPSCC.getStartDate().before(lastMergedPSCC.getEndDate())) {
                    //overlapping
                    
                    if (poppingPSCC.getEndDate().before(lastMergedPSCC.getEndDate())) {
                        //popping time window is "within" last merged object
                        
                        final int overlappingDays = Days.daysBetween(new DateTime(poppingPSCC.getStartDate()),
                            new DateTime(poppingPSCC.getEndDate())).getDays();
                        
                        if (overlappingDays >= (switchWindow - 1)) {
                            final PatientStageCombinationCount newPSCC = new PatientStageCombinationCount();
                            newPSCC.setPersonId(poppingPSCC.getPersonId());
                            newPSCC.setComboIds(lastMergedPSCC.getComboIds());
                            newPSCC.setStartDate(poppingPSCC.getEndDate());
                            newPSCC.setEndDate(lastMergedPSCC.getEndDate());
                            
                            poppingPSCC.setComboIds(mergeComboIds(lastMergedPSCC, poppingPSCC));
                            
                            lastMergedPSCC.setEndDate(poppingPSCC.getStartDate());
                            
                            //TODO - verify this more!!!
                            if (lastMergedPSCC.getStartDate().equals(lastMergedPSCC.getEndDate())) {
                                mergedList.remove(mergedList.size() - 1);
                            }
                            /**
                             * see java doc for method combinationSplit()
                             */
                            /*                            else {
                                                            final List<PatientStageCombinationCount> splittedEarlyMergedList = combinationSplit(
                                                                lastMergedPSCC, switchWindow);
                                                            if (splittedEarlyMergedList != null) {
                                                                mergedList.remove(mergedList.size() - 1);
                                                                mergedList.addAll(splittedEarlyMergedList);
                                                            }
                                                        }
                            */
                            mergedList.add(poppingPSCC);
                            
                            if (!newPSCCFromOriginalList) {
                                truncatedList.remove(0);
                            }
                            
                            /**
                             * see java doc for combinationSplit()
                             */
                            /*                            final List<PatientStageCombinationCount> splittedNewList = combinationSplit(newPSCC,
                                                            switchWindow);
                                                        if (splittedNewList != null) {
                                                            truncatedList.addAll(splittedNewList);
                                                        } else {
                                                            truncatedList.add(newPSCC);
                                                        }*/
                            
                            truncatedList.add(newPSCC);
                            
                            Collections.sort(truncatedList, this.patientStageCombinationCountDateComparator);
                        } else {
                            //no overlapping, just pop
                            mergedList.add(poppingPSCC);
                            
                            if (!newPSCCFromOriginalList) {
                                truncatedList.remove(0);
                            }
                            
                        }
                    } else if (poppingPSCC.getEndDate().after(lastMergedPSCC.getEndDate())) {
                        //popping object end date is after last merged object
                        
                        final int overlappingDays = Days.daysBetween(new DateTime(poppingPSCC.getStartDate()),
                            new DateTime(lastMergedPSCC.getEndDate())).getDays();
                        
                        if (overlappingDays >= (switchWindow - 1)) {
                            final PatientStageCombinationCount newPSCC = new PatientStageCombinationCount();
                            newPSCC.setPersonId(poppingPSCC.getPersonId());
                            newPSCC.setComboIds(poppingPSCC.getComboIds());
                            newPSCC.setStartDate(lastMergedPSCC.getEndDate());
                            newPSCC.setEndDate(poppingPSCC.getEndDate());
                            
                            poppingPSCC.setComboIds(mergeComboIds(lastMergedPSCC, poppingPSCC));
                            poppingPSCC.setEndDate(lastMergedPSCC.getEndDate());
                            
                            lastMergedPSCC.setEndDate(poppingPSCC.getStartDate());
                            
                            //TODO - verify this more!!!
                            if (lastMergedPSCC.getStartDate().equals(lastMergedPSCC.getEndDate())) {
                                mergedList.remove(mergedList.size() - 1);
                            }
                            /**
                             * see java doc for combinationSplit()
                             */
                            /*                            else {
                                                            final List<PatientStageCombinationCount> splittedEarlyMergedList = combinationSplit(
                                                                lastMergedPSCC, switchWindow);
                                                            if (splittedEarlyMergedList != null) {
                                                                mergedList.remove(mergedList.size() - 1);
                                                                mergedList.addAll(splittedEarlyMergedList);
                                                            }
                                                        }
                            */
                            mergedList.add(poppingPSCC);
                            
                            if (!newPSCCFromOriginalList) {
                                truncatedList.remove(0);
                            }
                            
                            /**
                             * see java doc for combinationSplit()
                             */
                            /*                            final List<PatientStageCombinationCount> splittedNewList = combinationSplit(newPSCC,
                                                            switchWindow);
                                                        if (splittedNewList != null) {
                                                            truncatedList.addAll(splittedNewList);
                                                        } else {
                                                            truncatedList.add(newPSCC);
                                                        }
                            */
                            truncatedList.add(newPSCC);
                            
                            Collections.sort(truncatedList, this.patientStageCombinationCountDateComparator);
                        } else {
                            //no overlapping, just pop
                            mergedList.add(poppingPSCC);
                            
                            if (!newPSCCFromOriginalList) {
                                truncatedList.remove(0);
                            }
                            
                        }
                        
                    } else if (poppingPSCC.getEndDate().equals(lastMergedPSCC.getEndDate())) {
                        //popping object end date is the same as last merged object
                        poppingPSCC.setComboIds(mergeComboIds(lastMergedPSCC, poppingPSCC));
                        
                        lastMergedPSCC.setEndDate(poppingPSCC.getStartDate());
                        
                        mergedList.add(poppingPSCC);
                        
                        if (!newPSCCFromOriginalList) {
                            truncatedList.remove(0);
                        }
                    }
                } else {
                    //no overlapping, just pop
                    mergedList.add(poppingPSCC);
                    
                    if (!newPSCCFromOriginalList) {
                        truncatedList.remove(0);
                    }
                }
            } else {
                //mergedList has no elements, just add the first one
                mergedList.add(poppingPSCC);
                
                //TODO -- check if still needed
                if (!newPSCCFromOriginalList) {
                    truncatedList.remove(0);
                }
            }
        } else {
            //TODO -- error logging
        }
    }
    
    private String mergeComboIds(final PatientStageCombinationCount pscc1, final PatientStageCombinationCount pscc2) {
        
        if ((pscc1 != null) && (pscc2 != null) && (pscc1.getComboIds() != null) && (pscc2.getComboIds() != null)) {
            final String[] pscc1ComboStringArray = pscc1.getComboIds().split("\\|");
            final List<String> psccCombos = new ArrayList<String>();
            psccCombos.addAll(Arrays.asList(pscc1ComboStringArray));
            
            final String[] pscc2ComboStringArray = pscc2.getComboIds().split("\\|");
            psccCombos.addAll(Arrays.asList(pscc2ComboStringArray));
            
            final Set<String> comboSet = new HashSet<String>(psccCombos);
            
            psccCombos.clear();
            psccCombos.addAll(comboSet);
            
            Collections.sort(psccCombos);
            
            return StringUtils.join(psccCombos, "|");
        }
        
        return null;
    }
    
    /**
     * This is to split combination PatientStageCombinationCount. For operating switch window
     * parameter and split old combined combos. May not need it for I think as long the combination
     * has bee combined, basically meaning they overlaps for long enough. When new drug comes in and
     * overlapping with the combined windows, the rest of the window (truncated part and the part
     * before current combination start date) should qualify the switch window too.
     * 
     * <pre>
     * 
     * For example:
     * 
     * A|B
     *  time1       time2
     *  l____________l
     * 
     * A|B|C
     *      time3       time4
     *      l____________l
     * 
     *  When C comes in and trucate combination A|B. The time between time1 and time3 should be considered
     *  as "qualified" combination too because even it's trunkcated by C, A and B's actually length is 
     *  between time1 and time2. So A and B should be considered as taken together.
     *  
     *  The same applies to following between time4 and time2 (A and B taking together):
     * 
     * A|B
     *  time1             time2
     *  l__________________l
     * 
     * A|B|C
     *      time3    time4
     *      l_______l
     * 
     * 
     * </pre>
     * 
     * @param pscc
     * @param switchWindow
     * @return
     */
    private List<PatientStageCombinationCount> combinationSplit(final PatientStageCombinationCount pscc,
                                                                final int switchWindow) {
        if (pscc != null) {
            
            if ((pscc.getStartDate() != null) && (pscc.getEndDate() != null)) {
                final int overlappingDays = Days.daysBetween(new DateTime(pscc.getStartDate()),
                    new DateTime(pscc.getEndDate())).getDays();
                
                if ((pscc.getComboIds() != null) && pscc.getComboIds().contains("|")
                        && (overlappingDays < (switchWindow - 1))) {
                    final String[] psccComboStringArray = pscc.getComboIds().split("\\|");
                    final List<String> psccCombos = new ArrayList<String>();
                    psccCombos.addAll(Arrays.asList(psccComboStringArray));
                    
                    final List<PatientStageCombinationCount> psccList = new ArrayList<PatientStageCombinationCount>();
                    for (final String comboID : psccCombos) {
                        final PatientStageCombinationCount returnPscc = new PatientStageCombinationCount();
                        returnPscc.setPersonId(pscc.getPersonId());
                        returnPscc.setComboIds(comboID);
                        returnPscc.setStartDate(pscc.getStartDate());
                        returnPscc.setEndDate(pscc.getEndDate());
                        
                        psccList.add(returnPscc);
                    }
                    
                    return psccList;
                }
            }
            //TODO - verify this as returned value
            //            final List<PatientStageCombinationCount> psccList = new ArrayList<PatientStageCombinationCount>();
            //            psccList.add(pscc);
            //            return psccList;
            return null;
        } else {
            return null;
        }
    }
}