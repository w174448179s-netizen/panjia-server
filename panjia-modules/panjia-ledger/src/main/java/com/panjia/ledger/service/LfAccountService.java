package com.panjia.ledger.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.ledger.domain.LfAccount;
import com.panjia.ledger.domain.LfStatementRow;
import com.panjia.ledger.mapper.LfAccountMapper;
import com.panjia.ledger.mapper.LfStatementRowMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 理房通门店对公账户配置。
 */
@Service
@RequiredArgsConstructor
public class LfAccountService {

    private final LfAccountMapper accountMapper;
    private final LfStatementRowMapper rowMapper;

    public PageResult<LfAccount> list(String keyword, String status, PageQuery pageQuery) {
        LambdaQueryWrapper<LfAccount> wrapper = new LambdaQueryWrapper<LfAccount>()
            .eq(StringUtils.isNotBlank(status), LfAccount::getStatus, status)
            .and(StringUtils.isNotBlank(keyword), w -> w.like(LfAccount::getStoreName, keyword)
                .or().like(LfAccount::getFilePrefix, keyword)
                .or().like(LfAccount::getAccountName, keyword))
            .orderByAsc(LfAccount::getStoreDeptId)
            .orderByAsc(LfAccount::getId);
        Page<LfAccount> page = accountMapper.selectPage(
            pageQuery == null ? new PageQuery().build() : pageQuery.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(LfAccount account, Long operatorId) {
        validate(account, null);
        account.setStatus(StringUtils.isBlank(account.getStatus()) ? "0" : account.getStatus());
        account.setCreateBy(operatorId);
        account.setCreateTime(LocalDateTime.now());
        account.setUpdateBy(operatorId);
        account.setUpdateTime(LocalDateTime.now());
        accountMapper.insert(account);
        return account.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(LfAccount account, Long operatorId) {
        if (account.getId() == null) {
            throw new ServiceException("缺少账户 ID");
        }
        LfAccount db = accountMapper.selectById(account.getId());
        if (db == null) {
            throw new ServiceException("账户配置不存在");
        }
        validate(account, account.getId());
        account.setUpdateBy(operatorId);
        account.setUpdateTime(LocalDateTime.now());
        accountMapper.updateById(account);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new ServiceException("缺少账户 ID");
        }
        long rows = rowMapper.selectCount(new LambdaQueryWrapper<LfStatementRow>()
            .eq(LfStatementRow::getAccountId, id));
        if (rows > 0) {
            throw new ServiceException("该账户已有流水数据，不能删除，可改为停用");
        }
        accountMapper.deleteById(id);
    }

    private void validate(LfAccount account, Long excludeId) {
        if (account.getStoreDeptId() == null) {
            throw new ServiceException("请选择门店");
        }
        if (StringUtils.isBlank(account.getStoreName())) {
            throw new ServiceException("门店名称不能为空");
        }
        if (StringUtils.isBlank(account.getFilePrefix())) {
            throw new ServiceException("文件名前缀不能为空");
        }
        if (StringUtils.isBlank(account.getAccountName())) {
            throw new ServiceException("对公账户名称不能为空");
        }
        String prefix = account.getFilePrefix().trim();
        account.setFilePrefix(prefix);
        long same = accountMapper.selectCount(new LambdaQueryWrapper<LfAccount>()
            .apply("LOWER(file_prefix) = {0}", prefix.toLowerCase())
            .ne(excludeId != null, LfAccount::getId, excludeId));
        if (same > 0) {
            throw new ServiceException("文件名前缀已存在，不能重复配置");
        }
        long storeCount = accountMapper.selectCount(new LambdaQueryWrapper<LfAccount>()
            .eq(LfAccount::getStoreDeptId, account.getStoreDeptId())
            .eq(LfAccount::getStatus, "0")
            .ne(excludeId != null, LfAccount::getId, excludeId));
        if (storeCount > 0 && "0".equals(account.getStatus())) {
            throw new ServiceException("该门店已有一个启用的对公账户，请先停用原账户");
        }
    }
}
