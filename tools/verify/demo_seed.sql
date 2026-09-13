INSERT OR IGNORE INTO folders(name,created_at) VALUES ('工作', strftime('%s','now')*1000);
INSERT OR IGNORE INTO folders(name,created_at) VALUES ('灵感', strftime('%s','now')*1000);
INSERT INTO notes(uuid,kind,title,body,color,folder_id,pinned,created_at,updated_at) VALUES
 ('demo-1',0,'快速上手','# 欢迎使用纯记

正文就是标准 Markdown,导出即用。

## 任务(点方块试试)
- [x] 安装完成
- [ ] 体验编辑器
- [ ] 新建一条笔记

## 列表与引用
1. 支持有序列表
2. 支持无序列表
- 直接书写即可

> 导出即是标准 Markdown 文件。

　　这一行是首行缩进。',0,1,1,strftime('%s','now')*1000,strftime('%s','now')*1000),
 ('demo-2',0,'灵感速记','- [ ] 想法:给卡片加封面
- [x] 换个纸色试试

![](test.png)',3,2,0,strftime('%s','now')*1000,strftime('%s','now')*1000),
 ('demo-3',0,'会议记录(示例)','# 会议记录

> 与会:所有人

1. 回顾上周
2. 讨论新功能
3. 排期',4,1,0,(strftime('%s','now')-3600)*1000,strftime('%s','now')*1000);
