-- ============================================================================
-- V200002 经纪人(014) 补授 OSS 文件上传相关按钮权限
-- 背景：
--   · 考勤修改申请（attendance_rectify）要求经纪人上传佐证截图，
--     ImageUpload 组件涉及三个接口，均被 Sa-Token 权限码拦截：
--       POST   /resource/oss/upload          → system:oss:upload（菜单 1601）
--       GET    /resource/oss/listByIds/{ids} → system:oss:query （菜单 1600，回显）
--       DELETE /resource/oss/{ids}           → system:oss:remove（菜单 1603，删除已传图）
--   · 经纪人在考勤查询页无独立 OSS 菜单入口，故只授 F 类按钮权限码，
--     不授权父级「文件管理」C 类菜单（0118），避免系统管理菜单对其可见
-- 说明：
--   · 人事/总监审批端通过 ossUrls 翻译后的 URL 直接预览图片，不经上述接口，无需授权
--   · 幂等：ON CONFLICT DO NOTHING，重复执行无副作用
-- ============================================================================

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000014, 1761400000000001600),  -- 文件查询（回显）
(1761300000000000014, 1761400000000001601),  -- 文件上传
(1761300000000000014, 1761400000000001603)   -- 文件删除
ON CONFLICT (role_id, menu_id) DO NOTHING;
