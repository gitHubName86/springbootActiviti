package com.ruoyi.activiti.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.activiti.domain.ProcessDefinition;
import com.ruoyi.activiti.service.ProcessDefinitionService;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import org.activiti.bpmn.converter.BpmnXMLConverter;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.editor.constants.ModelDataJsonConstants;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.repository.Model;
import org.activiti.engine.repository.ProcessDefinitionQuery;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.aspectj.weaver.loadtime.Aj;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.List;

@Controller
@RequestMapping("/definition")
public class ProcessDefinitionController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(ProcessDefinitionController.class);

    private String prefix = "definition";

    @Autowired
    private ProcessDefinitionService processDefinitionService;
    @Autowired
    private RepositoryService repositoryService;


    @GetMapping("/list")
    @ResponseBody
    public TableDataInfo list(ProcessDefinition processDefinition) {
        List<ProcessDefinition> list = processDefinitionService.listProcessDefinition(processDefinition);
        return getDataTable(list);
    }

    /**
     * ??????????????????
     */
    @Log(title = "????????????", businessType = BusinessType.INSERT)
    @PostMapping("/upload")
    @ResponseBody
    public AjaxResult upload(MultipartFile file) {
        try {
            if (!file.isEmpty()) {
                String extensionName = file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf('.') + 1);
                if (!"bpmn".equalsIgnoreCase(extensionName)
                        && !"zip".equalsIgnoreCase(extensionName)
                        && !"bar".equalsIgnoreCase(extensionName)) {
                    return AjaxResult.error("??????????????????????????? bpmn, zip ??? bar ?????????");
                }
                // p.s. ?????? FileUploadUtils.upload() ??????????????? fileName ????????? Constants.RESOURCE_PREFIX????????????
//                 ??????: FileUploadUtils.getPathFileName(...)
                String fileName = FileUploadUtils.upload(RuoYiConfig.getProfile() + "/processDefiniton", file);

                if (StringUtils.isNotBlank(fileName)) {
                    String realFilePath = RuoYiConfig.getProfile() + fileName.substring(Constants.RESOURCE_PREFIX.length());
                    processDefinitionService.deployProcessDefinition(realFilePath);
                    return AjaxResult.success();
                }
            }
            return AjaxResult.error("???????????????????????????");
        } catch (Exception e) {
            log.error("?????????????????????????????????", e);
            return AjaxResult.error(e.getMessage());
        }
    }

    @Log(title = "????????????", businessType = BusinessType.DELETE)
    @DeleteMapping("/remove/{ids}")
    @ResponseBody
    public AjaxResult remove(String ids) {
        try {
            return toAjax(processDefinitionService.deleteProcessDeploymentByIds(ids));
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

//    @Log(title = "????????????", businessType = BusinessType.EXPORT)
//    @PostMapping("/export")
//    @ResponseBody
//    public AjaxResult export() {
//        List<ProcessDefinition> list = processDefinitionService.listProcessDefinition(new ProcessDefinition());
//        ExcelUtil<ProcessDefinition> util = new ExcelUtil<>(ProcessDefinition.class);
//        return util.exportExcel(list, "??????????????????");
//    }

    @PostMapping("/suspendOrActiveApply")
    @ResponseBody
    public AjaxResult suspendOrActiveApply(@RequestBody ProcessDefinition processDefinition) {
        processDefinitionService.suspendOrActiveApply(processDefinition.getId(), processDefinition.getSuspendState());
        return AjaxResult.success();
    }

    /**
     * ??????????????????
     *
     * @param processDefinitionId ????????????ID
     * @param resourceName        ????????????
     */
    @RequestMapping(value = "/readResource")
    public void readResource(@RequestParam("processDefinitionId") String processDefinitionId, @RequestParam("resourceName") String resourceName, HttpServletResponse response)
            throws Exception {
        ProcessDefinitionQuery pdq = repositoryService.createProcessDefinitionQuery();
        org.activiti.engine.repository.ProcessDefinition pd = pdq.processDefinitionId(processDefinitionId).singleResult();

        // ??????????????????
        InputStream resourceAsStream = repositoryService.getResourceAsStream(pd.getDeploymentId(), resourceName);

        // ?????????????????????????????????
        byte[] b = new byte[1024];
        int len = -1;
        while ((len = resourceAsStream.read(b, 0, 1024)) != -1) {
            response.getOutputStream().write(b, 0, len);
        }
    }

    /**
     * ???????????????????????????
     *
     * @param processDefinitionId
     * @return
     * @throws UnsupportedEncodingException
     * @throws XMLStreamException
     */
    @PostMapping(value = "/convert2Model")
    @ResponseBody
    public AjaxResult convertToModel(@RequestBody String processDefinitionId)
            throws UnsupportedEncodingException, XMLStreamException {
        org.activiti.engine.repository.ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId).singleResult();

        InputStream bpmnStream = repositoryService.getResourceAsStream(processDefinition.getDeploymentId(),
                processDefinition.getResourceName());
        XMLInputFactory xif = XMLInputFactory.newInstance();
        InputStreamReader in = new InputStreamReader(bpmnStream, "UTF-8");
        XMLStreamReader xtr = xif.createXMLStreamReader(in);
        BpmnModel bpmnModel = new BpmnXMLConverter().convertToBpmnModel(xtr);

        BpmnJsonConverter converter = new BpmnJsonConverter();
        ObjectNode modelNode = converter.convertToJson(bpmnModel);
        Model modelData = repositoryService.newModel();
        modelData.setKey(processDefinition.getKey());
        modelData.setName(processDefinition.getResourceName());
        modelData.setCategory(processDefinition.getDeploymentId());

        ObjectNode modelObjectNode = new ObjectMapper().createObjectNode();
        modelObjectNode.put(ModelDataJsonConstants.MODEL_NAME, processDefinition.getName());
        modelObjectNode.put(ModelDataJsonConstants.MODEL_REVISION, 1);
        modelObjectNode.put(ModelDataJsonConstants.MODEL_DESCRIPTION, processDefinition.getDescription());
        modelData.setMetaInfo(modelObjectNode.toString());

        repositoryService.saveModel(modelData);

        repositoryService.addModelEditorSource(modelData.getId(), modelNode.toString().getBytes("utf-8"));

        return AjaxResult.success();
    }

}
