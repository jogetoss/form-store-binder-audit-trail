package org.joget.marketplace;

import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;

import java.util.ArrayList;
import java.util.HashMap;
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
            String auditTrailRemarksField = getPropertyString("from");
            String auditTrailRemarksColumn = getPropertyString("to");
            boolean tracksEverything = Boolean.parseBoolean(getPropertyString("tracksEverything"));

            AppDefinition appDef = AppUtil.getCurrentAppDefinition();
            FormDefinitionDao formDefinitionDao = (FormDefinitionDao) FormUtil.getApplicationContext().getBean("formDefinitionDao");
            FormDefinition formDef = formDefinitionDao.loadById(auditTrailFormID, appDef);
            String auditTrailTableName = formDef.getTableName();


            String auditTrailTableForeignKey = getPropertyString("foreignKey");

            //get the previous dataset (n-1)
            FormRowSet existingData = formData.getLoadBinderData(element);

            //find differences
            MapDifference diff = Maps.difference((Map) existingData.get(0), (Map) rows.get(0));
            JSONArray jsonArray = new JSONArray();

            Map differences = diff.entriesDiffering();
            String text = "";

            for (Object obj : differences.keySet()) {
                String id = element.getPropertyString(FormUtil.PROPERTY_ID);

                ValueDifference vd = ((ValueDifference) differences.get(obj));
                String fieldID = obj.toString();
                String before = vd.leftValue().toString();
                String after = vd.rightValue().toString();
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

                    FormRowSet selectRows = formData.getOptionsBinderData(controlElement, fieldID);
                    if(selectRows == null) {
                        selectRows = (FormRowSet) controlElementPropertyOptions.get("options");
                        if(selectRows == null) {
                            if (fieldID.equals(auditTrailRemarksField)) {
                                continue;
                            }
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
                //store changes into n
                FormRow currentRow = rows.get(0);
                currentRow.put("id", primaryKey);
                currentRow.put(auditTrailDiffField, jsonArray.toString());
                currentRow.put(auditTrailTextDiffField, text);
                currentRow.put(auditTrailRemarksColumn, rows.get(0).get(auditTrailRemarksField));


                //added empty row
                rows.remove(0);
                rows.add(currentRow);

                //retrieve n-1
                FormRowSet auditRows = new FormRowSet();
                FormRow currentTemp = rows.get(0);
                currentTemp.put(auditTrailTableForeignKey, primaryKey);
                currentTemp.setId(UUID.randomUUID().toString());
                auditRows.add(currentTemp);
                appService.storeFormData(auditTrailFormID, auditTrailTableName, auditRows, null);
                rows.get(0).setId(primaryKey);
            }

        }

        //proceed as usual
        return super.store(element, rows, formData);
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
}
