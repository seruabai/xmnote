DELETE FROM notes WHERE uuid='verify-1';
INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) VALUES ('verify-1',0,'验收笔记','第一行正文' || char(10) || '- [ ] 买牛奶' || char(10) || '![](test.png)',0,NULL,0,strftime('%s','now')*1000,strftime('%s','now')*1000);
SELECT id,body FROM notes WHERE uuid='verify-1';
