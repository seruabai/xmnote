INSERT INTO folders(name,created_at) VALUES ('工作', strftime('%s','now')*1000);
INSERT INTO folders(name,created_at) VALUES ('灵感', strftime('%s','now')*1000);
INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) VALUES
 ('demo-1',0,'快速上手','# 欢迎使用纯记\n\n正文就是标准 Markdown,导出即用。\n\n## 任务(点方块试试)\n- [x] 安装完成\n- [ ] 体验编辑器\n- [ ] 新建一条笔记\n\n## 列表与引用\n1. 支持有序列表\n2. 支持无序列表\n- 直接书写即可\n\n> 导出即是标准 Markdown 文件。\n\n　　这一行是首行缩进。',0,1,1,strftime('%s','now')*1000,strftime('%s','now')*1000),
 ('demo-2',0,'灵感速记','- [ ] 想法:给卡片加封面\n- [x] 换个纸色试试\n\n![](test.png)',3,2,0,strftime('%s','now')*1000,strftime('%s','now')*1000),
 ('demo-3',0,'会议记录(示例)','# 会议记录\n\n> 与会:所有人\n\n1. 回顾上周\n2. 讨论新功能\n3. 排期',4,1,0,(strftime('%s','now')-3600)*1000,strftime('%s','now')*1000);
INSERT INTO todos(uuid,parent_id,title,done,sort_index,created_at,updated_at) VALUES
 ('demo-todo-1',NULL,'买牛奶',0,0,strftime('%s','now')*1000,strftime('%s','now')*1000),
 ('demo-todo-2',NULL,'写周报',0,0,(strftime('%s','now')+86400)*1000,(strftime('%s','now')+86400)*1000),
 ('demo-todo-3',NULL,'回访客户',0,0,(strftime('%s','now')+172800)*1000,(strftime('%s','now')+172800)*1000);
SELECT 'notes',COUNT(*) FROM notes;
SELECT 'todos',COUNT(*) FROM todos WHERE trashed=0;
SELECT 'folders',COUNT(*) FROM folders;
