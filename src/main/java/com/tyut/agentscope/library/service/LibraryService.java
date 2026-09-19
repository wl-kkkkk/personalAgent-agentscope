package com.tyut.agentscope.library.service;

import com.tyut.agentscope.library.model.FileContentVO;
import com.tyut.agentscope.library.model.FileItemVO;
import com.tyut.agentscope.library.model.FolderVO;

import java.util.List;

public interface LibraryService {

    FolderVO getFolder(String userId);

    FolderVO changeFolder(String userId, String folder);

    List<FileItemVO> listFiles(String userId);

    FileContentVO readFile(String userId, long fileId);
}
