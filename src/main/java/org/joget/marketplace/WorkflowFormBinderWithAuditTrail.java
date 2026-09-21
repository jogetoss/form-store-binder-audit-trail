package org.joget.marketplace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.joget.apps.app.dao.FormDefinitionDao;
import org.joget.apps.app.model.FormDefinition;
import org.joget.apps.app.model.AppDefinition;
import org.joget.apps.app.service.AppPluginUtil;
import org.joget.apps.app.service.AppService;
import org.joget.apps.app.service.AppUtil;
import org.joget.apps.form.model.*;
import org.joget.apps.form.service.FormUtil;
import org.joget.apps.form.lib.WorkflowFormBinder;
import org.joget.commons.util.LogUtil;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Data binder that loads/stores data from the form database and also workflow
 * variables.
 */
public class WorkflowFormBinderWithAuditTrail extends WorkflowFormBinder {

    private final static String MESSAGE_PATH = "messages/form/WorkflowFormBinderWithAuditTrail";

    @Override
    public String getName() {
        return AppPluginUtil.getMessage("WorkflowFormBinderWithAuditTrail.name", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getVersion() {
        return "8.0.0";
    }

    @Override
    public String getDescription() {
        return AppPluginUtil.getMessage("WorkflowFormBinderWithAuditTrail.desc", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getLabel() {
        return AppPluginUtil.getMessage("WorkflowFormBinderWithAuditTrail.name", getClassName(), MESSAGE_PATH);
    }

    @Override
    public String getPropertyOptions() {
        return AppUtil.readPluginResource(getClassName(), "/properties/form/WorkflowFormBinderWithAuditTrail.json", null, true, MESSAGE_PATH);
    }

    @Override
    public FormRowSet load(Element element, String primaryKey, FormData formData) {
        return super.load(element, primaryKey, formData);
    }

    @Override
    public FormRowSet store(Element element, FormRowSet rows, FormData formData) {
        AppService appService = (AppService) FormUtil.getApplicationContext().getBean("appService");

        if (formData.getLoadBinderData(element) != null) {
            String primaryKey = formData.getPrimaryKeyValue();
            String auditTrailFormID = getPropertyString("auditTrailFormId");
            String auditTrailDiffField = getPropertyString("jsonDataField");
            String auditTrailTextDiffField = getPropertyString("textualDataField");
            String auditTrailSummaryField = getPropertyString("summaryField");
            String summaryTemplate = getPropertyString("summaryTemplate");
            boolean tracksEverything = Boolean.parseBoolean(getPropertyString("tracksEverything"));
            Object fieldMappingsProperty = getProperty("fieldMappings");
            Object fieldValuesProperty = getProperty("fieldValues");

            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            FormDefinitionDao formDefinitionDao = (FormDefinitionDao) FormUtil.getApplicationContext().getBean("formDefinitionDao");
            FormDefinition formDef = formDefinitionDao.loadById(auditTrailFormID, appDef);
            String auditTrailTableName = formDef.getTableName();


            String auditTrailTableForeignKey = getPropertyString("foreignKey");

            //get the previous dataset (n-1)
            FormRowSet existingData = formData.getLoadBinderData(element);

            //find differences
            Map differences = entriesDiffering((Map) existingData.get(0), (Map) rows.get(0));
            JSONArray jsonArray = new JSONArray();

            String text = "";

            for (Object obj : differences.keySet()) {
                String id = element.getPropertyString(FormUtil.PROPERTY_ID);

                ValueDiff vd = ((ValueDiff) differences.get(obj));
                String fieldID = obj.toString();
                String before = vd.left.toString();
                String after = vd.right.toString();
                String beforeContentID = "";
                String afterContentID = "";

                JSONObject jsonDiff = new JSONObject();
                try {
                    //Get element of field and its properties to check and extract data
                    Element controlElement = FormUtil.findElement(fieldID, element, formData);
                    Map<String, Object> controlElementPropertyOptions = controlElement.getProperties();
                    JSONObject jsonProperty = new JSONObject(controlElement.getDefaultPropertyValues());
                    String label = controlElementPropertyOptions.get("label").toString();

                    jsonDiff.accumulate("fieldID", fieldID); //i.e. full_name
                    jsonDiff.accumulate("fieldLabel", label); //i.e. full_name
                    Boolean printAfterBeforeWithLabel = false;
                    Boolean printAfterBefore = false;

                    //formData.getOptionsBinderData only returns a result if something already loaded and
                    //cached this element's options earlier in the same request (e.g. a live form submit).
                    //When store() runs outside that path (API-triggered saves, background process
                    //completion, etc.) the cache is empty, so fall back to loading the element's
                    //configured "Load Data From" options binder directly to resolve id-to-label pairs.
                    FormRowSet selectRows = formData.getOptionsBinderData(controlElement, fieldID);
                    if (selectRows == null) {
                        FormLoadBinder optionsBinder = FormUtil.findOptionsBinder(controlElement);
                        if (optionsBinder != null) {
                            try {
                                selectRows = optionsBinder.load(controlElement, primaryKey, formData);
                            } catch (Exception ex) {
                                LogUtil.error(this.getClassName(), ex, "Error loading options binder data for field " + fieldID);
                            }
                        }
                    }
                    //Some custom select-type elements (e.g. plugins backed by a DataList) don't wire a
                    //FormLoadBinder at all and instead implement FormReferenceDataRetriever to resolve
                    //their selected values back to full rows. Where such an element also exposes the
                    //"idField"/"displayField" properties (the convention used by, e.g., the marketplace
                    //"Dynamic Options Select Box" plugin) build a value/label row set from those rows too.
                    if (selectRows == null && controlElement instanceof FormReferenceDataRetriever) {
                        String idField = controlElement.getPropertyString("idField");
                        String displayField = controlElement.getPropertyString("displayField");
                        if (!displayField.isEmpty()) {
                            try {
                                FormRowSet referenceRows = ((FormReferenceDataRetriever) controlElement).loadFormRows(new String[0], formData);
                                if (referenceRows != null) {
                                    FormRowSet referenceSelectRows = new FormRowSet();
                                    for (FormRow referenceRow : referenceRows) {
                                        String idValue = !idField.isEmpty() ? referenceRow.getProperty(idField) : referenceRow.getId();
                                        FormRow optionRow = new FormRow();
                                        optionRow.setProperty("value", idValue);
                                        optionRow.setProperty("label", referenceRow.getProperty(displayField));
                                        referenceSelectRows.add(optionRow);
                                    }
                                    selectRows = referenceSelectRows;
                                }
                            } catch (Exception ex) {
                                LogUtil.error(this.getClassName(), ex, "Error loading reference data for field " + fieldID);
                            }
                        }
                    }
                    if(selectRows == null) {
                        selectRows = (FormRowSet) controlElementPropertyOptions.get("options");
                        if(selectRows == null) {
                            printAfterBefore = true;
                        } else {
                            printAfterBeforeWithLabel = true;
                        }
                    } else {
                        printAfterBeforeWithLabel = true;
                    }
                    
                    if(printAfterBeforeWithLabel){
                        String beforeContentLabel = "";
                        String afterContentLabel = "";
    
                        beforeContentID = before;
                        beforeContentLabel = mapValuesToLabels(before, selectRows);
                        afterContentID = after;
                        afterContentLabel = mapValuesToLabels(after, selectRows);

                        if (!beforeContentLabel.equals("") || !afterContentLabel.equals("")) {
                            jsonDiff.accumulate("beforeContentID", beforeContentID);
                            jsonDiff.accumulate("beforeContentValue", beforeContentLabel); //raw value, if it is a ID of a lookup, we need to populate the label
                            jsonDiff.accumulate("afterContentID", afterContentID);
                            jsonDiff.accumulate("afterContentValue", afterContentLabel); //raw value, if it is a ID of a lookup, we need to populate the label
                            text += "Field Label: " + label + ",\nField ID: " + fieldID + ","
                            + "\nBefore Content Value: " + beforeContentLabel + ",\nAfter Content Value: " + afterContentLabel + ","
                            + "\nBefore ID Value: " + beforeContentID + ",\nAfter ID Value: " + afterContentID + "\n\n";
                        } else {
                            printAfterBefore = true;
                        }
                    }

                    if(printAfterBefore){
                        jsonDiff.accumulate("afterContentValue", after); //raw value, if it is a ID of a lookup, we need to populate the label
                        jsonDiff.accumulate("beforeContentValue", before); //raw value, if it is a ID of a lookup, we need to populate the label
                        text += "Field Label: " + label + ",\nField ID: " + fieldID + ","
                                + "\nBefore Content Value: " + before + ",\nAfter Content Value: " + after + "\n\n";
                    }
                    
                } catch (JSONException ex) {
                    LogUtil.error(this.getClassName(), ex, "Error building changes object");
                }
                jsonArray.put(jsonDiff);

            }

            //only if there is changes
            if(jsonArray.length() != 0 || tracksEverything) {
                rows.get(0).put("id", primaryKey);

                //Build the audit row from scratch rather than reusing/mutating rows.get(0) directly.
                //FormRow carries pending file-upload temp-file state (tempFilePathMap) alongside its
                //field values; storing that same object here - before super.store() below processes it
                //for the parent form - caused any uploaded file to be moved into the audit trail form's
                //upload folder instead of the parent form's. Copying only specific field values (not the
                //FormRow object itself, and not via auditRow.putAll(rows.get(0)) - FormRow declares its
                //own putAll(FormRow) overload that *also* copies tempFilePathMap/deleteFilePathMap,
                //silently reintroducing this same bug) leaves the parent row's temp-file state untouched,
                //so only the parent form's own store() moves the upload, to the right folder.
                FormRow auditRow = new FormRow();

                //Optional: copy other field values from the parent row verbatim, as configured in the
                //"Additional Field Mappings" grid. Applied first so the plugin's own dedicated columns
                //below (foreign key, diff fields, remarks, summary) always take precedence if a mapping
                //happens to target the same column.
                if (fieldMappingsProperty instanceof Object[]) {
                    for (Object mapping : (Object[]) fieldMappingsProperty) {
                        if (!(mapping instanceof Map)) {
                            continue;
                        }
                        Map mappingRow = (Map) mapping;
                        Object fromObj = mappingRow.get("from");
                        Object toObj = mappingRow.get("to");
                        String mappingFrom = fromObj != null ? fromObj.toString().trim() : "";
                        String mappingTo = toObj != null ? toObj.toString().trim() : "";
                        if (!mappingFrom.isEmpty() && !mappingTo.isEmpty()) {
                            auditRow.put(mappingTo, rows.get(0).get(mappingFrom));
                        }
                    }
                }

                //Optional: insert arbitrary values - not necessarily copied from the current form - into
                //the audit trail record, as configured in the "Additional Field Values" grid. Each value
                //is processed as a Joget hash variable (e.g. #currentUser.fullName#) so it isn't limited
                //to static text. Applied here too, before the plugin's own dedicated columns below, so an
                //entry can never overwrite them.
                if (fieldValuesProperty instanceof Object[]) {
                    for (Object entry : (Object[]) fieldValuesProperty) {
                        if (!(entry instanceof Map)) {
                            continue;
                        }
                        Map valueRow = (Map) entry;
                        Object fieldObj = valueRow.get("field");
                        Object valueObj = valueRow.get("value");
                        String targetField = fieldObj != null ? fieldObj.toString().trim() : "";
                        String rawValue = valueObj != null ? valueObj.toString() : "";
                        if (!targetField.isEmpty()) {
                            auditRow.put(targetField, AppUtil.processHashVariable(rawValue, formData.getAssignment(), null, null));
                        }
                    }
                }

                auditRow.setId(UUID.randomUUID().toString());
                auditRow.put(auditTrailTableForeignKey, primaryKey);
                auditRow.put(auditTrailDiffField, jsonArray.toString());
                if (!auditTrailTextDiffField.isEmpty()) {
                    auditRow.put(auditTrailTextDiffField, text);
                }

                if (!auditTrailSummaryField.isEmpty() && !summaryTemplate.isEmpty()) {
                    String processedTemplate = AppUtil.processHashVariable(summaryTemplate, formData.getAssignment(), null, null);
                    auditRow.put(auditTrailSummaryField, renderSummaryTemplate(processedTemplate, jsonArray));
                }

                FormRowSet auditRows = new FormRowSet();
                auditRows.add(auditRow);
                appService.storeFormData(auditTrailFormID, auditTrailTableName, auditRows, null);
            }

        }

        //proceed as usual
        return super.store(element, rows, formData);
    }

    /**
     * Renders the "Summary Template" property into a human-readable summary of the field changes.
     * The template is plain text/HTML; a single <foreach>...</foreach> block, if present, is repeated
     * once per entry in fieldDiffs, with {key} placeholders substituted from that entry's JSON properties
     * (fieldID, fieldLabel, beforeContentValue, afterContentValue, and beforeContentID/afterContentID when
     * present). Text outside the <foreach> block is emitted once, unchanged (hash variables such as
     * #currentUser.fullName# are expected to already be processed by the caller).
     */
    private static String renderSummaryTemplate(String template, JSONArray fieldDiffs) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        String header = template;
        String loopBlock = "";
        String footer = "";

        int foreachStart = template.indexOf("<foreach>");
        int foreachEnd = template.indexOf("</foreach>");
        if (foreachStart != -1 && foreachEnd != -1 && foreachEnd > foreachStart) {
            header = template.substring(0, foreachStart);
            loopBlock = template.substring(foreachStart + "<foreach>".length(), foreachEnd);
            footer = template.substring(foreachEnd + "</foreach>".length());
        }

        StringBuilder summary = new StringBuilder();
        summary.append(header);
        for (int i = 0; i < fieldDiffs.length(); i++) {
            JSONObject fieldDiff = fieldDiffs.optJSONObject(i);
            if (fieldDiff == null) {
                continue;
            }
            String rendered = loopBlock;
            for (Iterator it = fieldDiff.keys(); it.hasNext();) {
                String key = (String) it.next();
                rendered = rendered.replace("{" + key + "}", fieldDiff.optString(key, ""));
            }
            //strip placeholders that had no matching key in this entry (e.g. ID placeholders on non-lookup fields)
            rendered = rendered.replaceAll("\\{[a-zA-Z0-9_]+\\}", "");
            summary.append(rendered);
        }
        summary.append(footer);
        return summary.toString();
    }

    public static String mapValuesToLabels(String rawValue, FormRowSet options) {
        if (rawValue == null || options == null) {
            return "";
        }

        // Build value-to-label map
        Map<String, String> valueToLabelMap = new HashMap<>();
        for (FormRow row : options) {
            String value = row.get("value") != null ? row.get("value").toString().trim() : "";
            String label = row.get("label") != null ? row.get("label").toString().trim() : "";
            valueToLabelMap.put(value, label);
        }

        // Split input and map each value
        String[] values = rawValue.split(";");
        List<String> mappedLabels = new ArrayList<>();
        for (String val : values) {
            String trimmedVal = val.trim();
            String mappedLabel = valueToLabelMap.getOrDefault(trimmedVal, trimmedVal); // fallback to value if label not found

            if (mappedLabel.isEmpty()) {
                mappedLabel = "\"\"";
            }

            mappedLabels.add(mappedLabel);
        }

        boolean allEmptyStrings = true;
        for (String label : mappedLabels) {
            if (!"\"\"".equals(label)) {
                allEmptyStrings = false;
                break;
            }
        }
        if (allEmptyStrings) {
            return "";
        }

        return String.join(";", mappedLabels);
    }

    /**
     * Plain-Java replacement for Guava's Maps.difference(left, right).entriesDiffering().
     * Guava is not embedded/imported by this bundle's OSGi manifest, so relying on it
     * throws NoClassDefFoundError at runtime even though it is available at compile time
     * (pulled in transitively, with provided scope, via wflow-core).
     *
     * Returns only the keys present in both maps whose values differ (null-safe equality),
     * matching Guava's entriesDiffering() semantics.
     */
    private static Map<Object, ValueDiff> entriesDiffering(Map left, Map right) {
        Map<Object, ValueDiff> result = new HashMap<>();
        for (Object key : left.keySet()) {
            if (right.containsKey(key)) {
                Object leftValue = left.get(key);
                Object rightValue = right.get(key);
                boolean equal = (leftValue == null) ? (rightValue == null) : leftValue.equals(rightValue);
                if (!equal) {
                    result.put(key, new ValueDiff(leftValue, rightValue));
                }
            }
        }
        return result;
    }

    private static class ValueDiff {
        final Object left;
        final Object right;

        ValueDiff(Object left, Object right) {
            this.left = left;
            this.right = right;
        }
    }
}
