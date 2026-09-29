(function () {
    'use strict';
    const $ = id => document.getElementById(id);
    const root = window.location.pathname.replace(/\/console\/?$/, '');
    let current = null, start = 0, busy = false, recoveryCursor = '0:0';
    async function request(path, body) {
        const response = await fetch(root + path, body === undefined ? {credentials: 'same-origin'} : {
            method: 'POST', credentials: 'same-origin',
            headers: {'Content-Type': 'application/json', 'X-Workflow-Admin': '1'}, body: JSON.stringify(body)
        });
        const text = await response.text();
        let data;
        try { data = JSON.parse(text); } catch (_) { throw new Error('服务器未返回有效响应，请检查登录状态或 Jira 日志。'); }
        if (!response.ok) throw new Error(data.error || '请求失败');
        return data;
    }
    function report(text, error) { $('message').textContent = text; $('message').className = error ? 'error' : 'success'; }
    async function run(operation) {
        if (busy) return;
        busy = true; document.body.setAttribute('aria-busy', 'true');
        try { await operation(); } catch (error) { report(error.message, true); }
        finally { busy = false; document.body.removeAttribute('aria-busy'); }
    }
    async function list() {
        const rows = await request('/workflows?start=' + start + '&limit=50&q=' + encodeURIComponent($('query').value));
        $('workflows').replaceChildren(new Option('请选择', ''));
        rows.forEach(row => $('workflows').add(new Option(row.name, row.name)));
        $('previous').disabled = start === 0; $('next').disabled = rows.length < 50;
        $('details').hidden = true; current = null;
    }
    async function load(name) {
        current = await request('/configuration?workflow=' + encodeURIComponent(name));
        const config = current.configuration || {};
        const inherited = new Set(config.inheritedStatuses || []);
        $('workflow-name').textContent = current.name;
        $('workflow-status').textContent = (config.parent ? '父流程：' + config.parent + '；' : '父模板；') +
            '同步状态：' + (config.syncStatus || '未配置') + (config.syncReason ? '；' + config.syncReason : '') +
            (current.hasDraft ? '；存在 Jira 草稿，请按原生机制处理。' : '');
        $('states').replaceChildren();
        current.statuses.forEach(status => {
            const row = document.createElement('tr');
            const nameCell = document.createElement('td'); nameCell.textContent = status.name + ' (' + status.id + ')';
            const configCell = document.createElement('td');
            const publishedCell = document.createElement('td');
            const stage = (config.publishedStages || {})[status.id] || status.id;
            const saved = (config.publishedProgress || {})[stage];
            publishedCell.textContent = saved === undefined ? '—' : saved + '%';
            if (!config.parent) {
                const input = document.createElement('input'); input.type = 'number'; input.min = '0'; input.max = '100'; input.step = '0.01'; input.required = true;
                input.dataset.status = status.id; input.setAttribute('aria-label', status.name + '累计进度');
                const draft = (config.draftProgress || {})[status.id]; input.value = draft === undefined ? '' : draft;
                configCell.appendChild(input);
            } else if (inherited.has(status.id)) configCell.textContent = '继承父流程';
            else {
                const select = document.createElement('select'); select.required = true; select.dataset.status = status.id;
                select.setAttribute('aria-label', status.name + '所属父阶段'); select.add(new Option('选择父阶段', ''));
                inherited.forEach(id => { const target = current.statuses.find(s => s.id === id); select.add(new Option(target ? target.name : id, id)); });
                select.value = (config.draftStages || {})[status.id] || ''; configCell.appendChild(select);
            }
            row.append(nameCell, configCell, publishedCell); $('states').appendChild(row);
        });
        $('activate').hidden = !!config.parent; $('activate').disabled = current.hasDraft || !config.version;
        $('retry').hidden = !config.parent; $('resolve').hidden = !config.parent;
        $('preview-sync').hidden = !config.parent; $('sync-preview').hidden = true;
        $('child-form').hidden = !!config.parent || !config.publishedRevision;
        $('details').hidden = false;
        await audit();
    }
    async function audit() {
        if (!current) return;
        const rows = await request('/audit?workflow=' + encodeURIComponent(current.name) + '&limit=50');
        $('audit').replaceChildren();
        rows.forEach(row => { const item = document.createElement('li'); item.textContent =
            new Date(row.time).toLocaleString() + ' · ' + row.actor + ' · ' + row.action + ' · v' + row.version; $('audit').appendChild(item); });
    }
    function base() { return {workflow: current.name, version: current.configuration ? current.configuration.version : 0, fingerprint: current.fingerprint, parentRevision: current.parentRevision}; }
    $('search-form').addEventListener('submit', event => { event.preventDefault(); run(async () => { start = 0; await list(); }); });
    $('previous').onclick = () => run(async () => { start = Math.max(0, start - 50); await list(); });
    $('next').onclick = () => run(async () => { start += 50; await list(); });
    $('workflows').onchange = () => { if ($('workflows').value) run(() => load($('workflows').value)); };
    $('progress-form').addEventListener('submit', event => { event.preventDefault(); run(async () => {
        const body = Object.assign(base(), {progress: {}, stages: {}});
        $('states').querySelectorAll('input[data-status]').forEach(input => { body.progress[input.dataset.status] = Number(input.value); });
        $('states').querySelectorAll('select[data-status]').forEach(select => { body.stages[select.dataset.status] = select.value; });
        await request('/draft', body); await load(body.workflow); report('进度草稿已保存。');
    }); });
    $('activate').onclick = () => run(async () => { const body = base(); await request('/activate', body); await load(body.workflow); report('进度规则已生效，已有 Issue 值保持不变。'); });
    $('retry').onclick = () => run(async () => { const body = base(); await request('/retry', body); await load(body.workflow); report('已重试，请查看同步状态。'); });
    $('preview-sync').onclick = () => run(async () => {
        const plan = await request('/preview-sync', base());
        $('sync-preview').textContent = '拟同步路径（状态 ID）：\n' + plan.paths.map(path => path.from + ' → ' + path.to).join('\n') +
            '\n扩展状态所属阶段：\n' + Object.entries(plan.stages).map(([state, stage]) => state + '：' + stage).join('\n');
        $('sync-preview').hidden = false; report('合并预览已生成，尚未修改或发布工作流。');
    });
    $('resolve').onclick = () => run(async () => { const body = base(); await request('/resolve', body); await load(body.workflow); report('节点路径及进度规则已校验，子流程进度规则已生效。'); });
    $('child-form').addEventListener('submit', event => { event.preventDefault(); run(async () => {
        const body = Object.assign(base(), {child: $('child-name').value, description: $('child-description').value});
        await request('/children', body); await load(body.workflow); report('子流程已创建，可通过 Jira 工作流方案使用。');
    }); });
    $('audit-refresh').onclick = () => run(audit);
    $('setup-field').onclick = () => run(async () => {
        const fields = await request('/field', {});
        $('field-info').textContent = fields.map(field => field.name + '：' + field.id + '；JQL 示例：' + field.jql).join('；');
        report('字段已准备，可在 Jira 中配置显示界面和列表列。');
    });
    async function recoveries(cursor) {
        const page = await request('/../recovery?cursor=' + encodeURIComponent(cursor));
        recoveryCursor = page.nextCursor; $('recovery-next').disabled = !recoveryCursor;
        $('recoveries').replaceChildren();
        page.rows.forEach(receipt => {
            const row = document.createElement('tr');
            [String(receipt.issueId), receipt.workflow + ' / ' + receipt.targetStatus, receipt.state + (receipt.error ? '：' + receipt.error : '')].forEach(value => {
                const cell = document.createElement('td'); cell.textContent = value; row.appendChild(cell);
            });
            const cell = document.createElement('td'), button = document.createElement('button'); button.textContent = '重试'; button.type = 'button';
            button.onclick = () => run(async () => {
                await request('/../recovery/retry', {issueId: receipt.issueId, transitionId: receipt.transitionId});
                await recoveries('0:0'); report('恢复请求已执行。');
            });
            cell.appendChild(button); row.appendChild(cell); $('recoveries').appendChild(row);
        });
    }
    $('recovery-refresh').onclick = () => run(() => recoveries('0:0'));
    $('recovery-next').onclick = () => run(() => recoveries(recoveryCursor));
    run(list);
}());
