package com.xuecheng.media.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import com.xuecheng.base.exception.XueChengPlusException;
import com.xuecheng.base.model.PageParams;
import com.xuecheng.base.model.PageResult;
import com.xuecheng.base.model.RestResponse;
import com.xuecheng.media.mapper.MediaFilesMapper;
import com.xuecheng.media.mapper.MediaProcessMapper;
import com.xuecheng.media.model.dto.QueryMediaParamsDto;
import com.xuecheng.media.model.dto.UploadFileParamsDto;
import com.xuecheng.media.model.dto.UploadFileResultDto;
import com.xuecheng.media.model.po.MediaFiles;
import com.xuecheng.media.model.po.MediaProcess;
import com.xuecheng.media.service.MediaFileService;
import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.DeleteError;
import io.minio.messages.DeleteObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.*;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Slf4j
@Service
public class MediaFileServiceImpl implements MediaFileService {
    @Autowired
    MediaFileService currentProxy;

    @Autowired
    MediaFilesMapper mediaFilesMapper;

    @Autowired
    MediaProcessMapper mediaProcessMapper;

    @Autowired
    MinioClient minioClient;

    @Value("${minio.bucket.files}")
    private String bucket_files;

    @Value("${minio.bucket.videofiles}")
    private String video_files;


    /**
     * @param companyId           机构id
     * @param uploadFileParamsDto 文件信息
     * @param bytes               文件字节数组
     * @param folder              桶下边的子目录
     * @param objectName          对象名称
     */
    @Override
    public UploadFileResultDto uploadFile(Long companyId, UploadFileParamsDto uploadFileParamsDto, byte[] bytes, String folder, String objectName) {
        String fileMD5 = DigestUtils.md5DigestAsHex(bytes);
        if (StringUtils.isEmpty(folder)) {
            // 如果目录不存在，则自动生成一个目录
            folder = getFileFolder(true, true, true);
        } else if (!folder.endsWith("/")) {
            // 如果目录末尾没有 / ，替他加一个
            folder = folder + "/";
        }
        if (StringUtils.isEmpty(objectName)) {
            // 如果文件名为空，则设置其默认文件名为文件的md5码 + 文件后缀名
            String filename = uploadFileParamsDto.getFilename();
            objectName = fileMD5 + filename.substring(filename.lastIndexOf("."));
        }
        objectName = folder + objectName;
        try {
            addMediaFilesToMinIO(bytes, bucket_files, objectName);
            MediaFiles mediaFiles = currentProxy.addMediaFilesToDB(companyId, uploadFileParamsDto, objectName, fileMD5, bucket_files);
            UploadFileResultDto uploadFileResultDto = new UploadFileResultDto();
            BeanUtils.copyProperties(mediaFiles, uploadFileResultDto);
            return uploadFileResultDto;
        } catch (Exception e) {
            log.debug("上传过程中出错：{}", e.getMessage());
            XueChengPlusException.cast("上传过程中出错" + e.getMessage());
        }
        return null;
    }

    /**
     * 将文件信息添加到文件表
     *
     * @param companyId           机构id
     * @param uploadFileParamsDto 上传文件的信息
     * @param objectName          对象名称
     * @param fileMD5             文件的md5码
     * @param bucket              桶
     */
    @Transactional
    @Override
    public MediaFiles addMediaFilesToDB(Long companyId, UploadFileParamsDto uploadFileParamsDto, String objectName, String fileMD5, String bucket) {
        // 根据文件名获取Content-Type
        String contentType = getContentType(objectName);
        // 保存到数据库
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMD5);
        if (mediaFiles == null) {
            mediaFiles = new MediaFiles();
            BeanUtils.copyProperties(uploadFileParamsDto, mediaFiles);
            mediaFiles.setId(fileMD5);
            mediaFiles.setFileId(fileMD5);
            mediaFiles.setCompanyId(companyId);
            mediaFiles.setBucket(bucket);
            mediaFiles.setCreateDate(LocalDateTime.now());
            mediaFiles.setStatus("1");
            mediaFiles.setFilePath(objectName);
            if (contentType.contains("image") || contentType.contains("mp4")) {
                mediaFiles.setUrl("/" + bucket + "/" + objectName);
            }
            // 查阅数据字典，002003表示审核通过
            mediaFiles.setAuditStatus("002003");
        }
        int insert = mediaFilesMapper.insert(mediaFiles);
        if (insert <= 0) {
            XueChengPlusException.cast("保存文件信息失败");
        }
        // 如果是avi视频，则额外添加至视频待处理表
        if ("video/x-msvideo".equals(contentType)) {
            MediaProcess mediaProcess = new MediaProcess();
            BeanUtils.copyProperties(mediaFiles, mediaProcess);
            mediaProcess.setStatus("1"); // 未处理
            int processInsert = mediaProcessMapper.insert(mediaProcess);
            if (processInsert <= 0) {
                XueChengPlusException.cast("保存avi视频到待处理表失败");
            }
        }
        return mediaFiles;
    }

    /**
     * 判断文件是否存在
     *
     * @param fileMd5 文件的md5
     * @return
     */
    @Override
    public RestResponse<Boolean> checkFile(String fileMd5) {
        MediaFiles mediaFiles = mediaFilesMapper.selectById(fileMd5);
        // 数据库中不存在，则直接返回false 表示不存在
        if (mediaFiles == null) {
            return RestResponse.success(false);
        }
        // 若数据库中存在，根据数据库中的文件信息，则继续判断bucket中是否存在
        try {
            InputStream inputStream = minioClient.getObject(GetObjectArgs
                    .builder()
                    .bucket(mediaFiles.getBucket())
                    .object(mediaFiles.getFilePath())
                    .build());
            if (inputStream == null) {
                return RestResponse.success(false);
            }
        } catch (Exception e) {
            return RestResponse.success(false);
        }
        return RestResponse.success(true);
    }

    @Override
    public RestResponse<Boolean> checkChunk(String fileMd5, int chunkIndex) {
        // 获取分块目录
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        String chunkFilePath = chunkFileFolderPath + chunkIndex;
        try {
            // 判断分块是否存在
            InputStream inputStream = minioClient.getObject(GetObjectArgs
                    .builder()
                    .bucket(video_files)
                    .object(chunkFilePath)
                    .build());
            // 不存在返回false
            if (inputStream == null) {
                return RestResponse.success(false);
            }
        } catch (Exception e) {
            // 出异常也返回false
            return RestResponse.success(false);
        }
        // 否则返回true
        return RestResponse.success(true);
    }

    /**
     * 上传分块
     *
     * @param fileMd5 文件MD5
     * @param chunk   分块序号
     * @param bytes   文件字节
     * @return
     */
    @Override
    public RestResponse uploadChunk(String fileMd5, int chunk, byte[] bytes) {
        // 分块文件路径
        String chunkFilePath = getChunkFileFolderPath(fileMd5) + chunk;
        try {
            addMediaFilesToMinIO(bytes, video_files, chunkFilePath);
            return RestResponse.success(true);
        } catch (Exception e) {
            log.debug("上传分块文件：{}失败：{}", chunkFilePath, e.getMessage());
        }
        return RestResponse.validfail("上传文件失败", false);
    }

    @Override
    public RestResponse mergeChunks(Long companyId, String fileMd5, int chunkTotal, UploadFileParamsDto uploadFileParamsDto) {
        //分块文件所在目录
        String chunkFileFolderPath = getChunkFileFolderPath(fileMd5);
        //找到所有的分块文件
        List<ComposeSource> sources = new ArrayList<>();
        for (int i = 0; i < chunkTotal; i++){
            ComposeSource composeSource = ComposeSource.builder()
                    .bucket(video_files)
                    .object(chunkFileFolderPath + i)
                    .build();
            sources.add(composeSource);
        }
        //源文件名称
        String filename = uploadFileParamsDto.getFilename();
        //扩展名
        String extension = filename.substring(filename.lastIndexOf("."));
        //合并后文件的objectname
        String objectName = getFilePathByMd5(fileMd5, extension);
        //指定合并后的objectName等信息
        ComposeObjectArgs composeObjectArgs = ComposeObjectArgs.builder()
                .bucket(video_files)
                .object(objectName)//合并后的文件的objectname
                .sources(sources)//指定源文件
                .build();
        //===========合并文件============
        //报错size 1048576 must be greater than 5242880，minio默认的分块文件大小为5M
        try {
            minioClient.composeObject(composeObjectArgs);
        } catch (Exception e) {
            e.printStackTrace();
            log.error("合并文件出错,bucket:{},objectName:{},错误信息:{}",video_files,objectName,e.getMessage());
            return RestResponse.validfail("文件合并出错", false);
        }

        //===========校验合并后的和源文件是否一致，视频上传才成功===========
        //先下载合并后的文件
        File file = downloadFileFromMinIO(video_files, objectName);
        try(FileInputStream fileInputStream = new FileInputStream(file)){
            //计算合并后文件的md5
            String mergeFile_md5 = DigestUtils.md5DigestAsHex(fileInputStream);
            //比较原始md5和合并后文件的md5
            if(!fileMd5.equals(mergeFile_md5)){
                log.error("校验合并文件md5值不一致,原始文件:{},合并文件:{}",fileMd5,mergeFile_md5);
                return RestResponse.validfail("文件校验失败", false);
            }
            //文件大小
            uploadFileParamsDto.setFileSize(file.length());
        }catch (Exception e) {
            return RestResponse.validfail("文件校验失败", false);
        }

        //==============将文件信息入库============
        MediaFiles mediaFiles = currentProxy.addMediaFilesToDB(companyId, uploadFileParamsDto, fileMd5, video_files, objectName);
        if(mediaFiles == null){
            return RestResponse.validfail("文件入库失败", false);
        }
        //==========清理分块文件=========
        clearChunkFiles(chunkFileFolderPath,chunkTotal);

        return RestResponse.success(true);
    }

    /**
     * 清除分块文件
     * @param chunkFileFolderPath 分块文件路径
     * @param chunkTotal 分块文件总数
     */
    private void clearChunkFiles(String chunkFileFolderPath,int chunkTotal){
        Iterable<DeleteObject> objects =  Stream.iterate(0, i -> ++i)
                .limit(chunkTotal)
                .map(i -> new DeleteObject(chunkFileFolderPath+ i))
                .collect(Collectors.toList());;
        RemoveObjectsArgs removeObjectsArgs = RemoveObjectsArgs.builder()
                .bucket(video_files)
                .objects(objects)
                .build();
        Iterable<Result<DeleteError>> results = minioClient.removeObjects(removeObjectsArgs);
        //要想真正删除
        results.forEach(f->{
            try {
                DeleteError deleteError = f.get();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

    }

    /**
     * 从minio下载文件
     * @param bucket 桶
     * @param objectName 对象名称
     * @return 下载后的文件
     */
    public File downloadFileFromMinIO(String bucket,String objectName){
        //临时文件
        File minioFile = null;
        FileOutputStream outputStream = null;
        try{
            InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .build());
            //创建临时文件
            minioFile=File.createTempFile("minio", ".merge");
            outputStream = new FileOutputStream(minioFile);
            IOUtils.copy(stream,outputStream);
            return minioFile;
        } catch (Exception e) {
            e.printStackTrace();
        }finally {
            if(outputStream!=null){
                try {
                    outputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }


    @Override
    public MediaFiles getFileById(String id) {
        MediaFiles mediaFiles = mediaFilesMapper.selectById(id);
        if (mediaFiles == null || StringUtils.isEmpty(mediaFiles.getUrl())) {
            XueChengPlusException.cast("视频还没有转码处理");
        }
        return mediaFiles;
    }

    /**
     * 根据MD5和文件扩展名，生成文件路径，例 /2/f/2f6451sdg/2f6451sdg.mp4
     *
     * @param fileMd5   文件MD5
     * @param extension 文件扩展名
     * @return
     */
    @Override
    public String getFilePathByMd5(String fileMd5, String extension) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + fileMd5 + extension;
    }

    /**
     * 将本地文件上传到minio
     *
     * @param filePath   本地文件路径
     * @param bucket     桶
     * @param objectName 对象名称
     */
    @Override
    public void addMediaFilesToMinIO(String filePath, String bucket, String objectName) {
        String contentType = getContentType(objectName);
        try {
            minioClient.uploadObject(UploadObjectArgs
                    .builder()
                    .bucket(bucket)
                    .object(objectName)
                    .filename(filePath)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            XueChengPlusException.cast("上传到文件系统出错:" + e.getMessage());
        }
    }

    /**
     * 下载分块文件
     *
     * @param fileMd5    文件的MD5
     * @param chunkTotal 总块数
     * @return 分块文件数组
     */
    private File[] checkChunkStatus(String fileMd5, int chunkTotal) {
        // 作为结果返回
        File[] files = new File[chunkTotal];
        // 获取分块文件目录
        String chunkFileFolder = getChunkFileFolderPath(fileMd5);
        for (int i = 0; i < chunkTotal; i++) {
            // 获取分块文件路径
            String chunkFilePath = chunkFileFolder + i;
            File chunkFile = null;
            try {
                // 创建临时的分块文件
                chunkFile = File.createTempFile("chunk" + i, null);
            } catch (Exception e) {
                XueChengPlusException.cast("创建临时分块文件出错：" + e.getMessage());
            }

            chunkFile = downloadFileFromMinio(chunkFile, video_files, chunkFilePath);
            files[i] = chunkFile;
        }
        return files;
    }


    /**
     * 从Minio中下载文件
     *
     * @param file       目标文件
     * @param bucket     桶
     * @param objectName 桶内文件路径
     * @return
     */
    @Override
    public File downloadFileFromMinio(File file, String bucket, String objectName) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(file);
             InputStream inputStream = minioClient.getObject(GetObjectArgs
                     .builder()
                     .bucket(bucket)
                     .object(objectName)
                     .build())) {
            IOUtils.copy(inputStream, fileOutputStream);
            return file;
        } catch (Exception e) {
            XueChengPlusException.cast("查询文件分块出错");
        }
        return null;
    }

    /**
     * 获取分块文件的目录，例如文件的md5码为  1f2465f， 那么该文件的分块放在 /1/f/1f2465f下，即前两级目录为md5的前两位
     *
     * @param fileMd5
     * @return
     */
    private String getChunkFileFolderPath(String fileMd5) {
        return fileMd5.substring(0, 1) + "/" + fileMd5.substring(1, 2) + "/" + fileMd5 + "/" + "chunk" + "/";
    }

    /**
     * @param bytes      文件字节数组
     * @param bucket     桶
     * @param objectName 对象名称 23/02/15/porn.mp4
     */
    public void addMediaFilesToMinIO(byte[] bytes, String bucket, String objectName) {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);
        String contentType = getContentType(objectName);
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(byteArrayInputStream, byteArrayInputStream.available(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            log.debug("上传到文件系统出错:{}", e.getMessage());
            throw new XueChengPlusException("上传到文件系统出错");
        }
    }

    /**
     * 根据objectName获取对应的MimeType
     *
     * @param objectName 对象名称
     * @return
     */
    private static String getContentType(String objectName) {
        String contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE; // 默认content-type为未知二进制流
        if (objectName.contains(".")) { // 判断对象名是否包含 .
            // 有 .  则划分出扩展名
            String extension = objectName.substring(objectName.lastIndexOf("."));
            // 根据扩展名得到content-type，如果为未知扩展名，例如 .abc之类的东西，则会返回null
            ContentInfo extensionMatch = ContentInfoUtil.findExtensionMatch(extension);
            // 如果得到了正常的content-type，则重新赋值，覆盖默认类型
            if (extensionMatch != null) {
                contentType = extensionMatch.getMimeType();
            }
        }
        return contentType;
    }

    /**
     * 自动生成目录
     *
     * @param year  是否包含年
     * @param month 是否包含月
     * @param day   是否包含日
     */

    private String getFileFolder(boolean year, boolean month, boolean day) {
        StringBuffer stringBuffer = new StringBuffer();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        String dateString = dateFormat.format(new Date());
        String[] split = dateString.split("-");
        if (year) {
            stringBuffer.append(split[0]).append("/");
        }
        if (month) {
            stringBuffer.append(split[1]).append("/");
        }
        if (day) {
            stringBuffer.append(split[2]).append("/");
        }
        return stringBuffer.toString();
    }


    @Override
    public PageResult<MediaFiles> queryMediaFiles(Long companyId, PageParams pageParams, QueryMediaParamsDto queryMediaParamsDto) {

        //构建查询条件对象
        LambdaQueryWrapper<MediaFiles> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(!StringUtils.isEmpty(queryMediaParamsDto.getFilename()), MediaFiles::getFilename, queryMediaParamsDto.getFilename());
        queryWrapper.eq(!StringUtils.isEmpty(queryMediaParamsDto.getFileType()), MediaFiles::getFileType, queryMediaParamsDto.getFileType());
        //分页对象
        Page<MediaFiles> page = new Page<>(pageParams.getPageNo(), pageParams.getPageSize());
        // 查询数据内容获得结果
        Page<MediaFiles> pageResult = mediaFilesMapper.selectPage(page, queryWrapper);
        // 获取数据列表
        List<MediaFiles> list = pageResult.getRecords();
        // 获取数据总数
        long total = pageResult.getTotal();
        // 构建结果集
        PageResult<MediaFiles> mediaListResult = new PageResult<>(list, total, pageParams.getPageNo(), pageParams.getPageSize());
        return mediaListResult;
    }
}
