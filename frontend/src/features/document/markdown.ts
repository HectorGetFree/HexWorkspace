function escapeHtml(value: string): string { return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }
export function markdownToHtml(markdown: string): string {
  return markdown.split(/\r?\n/).map((line) => {
    if (line.startsWith('### ')) return `<h3>${escapeHtml(line.slice(4))}</h3>`
    if (line.startsWith('## ')) return `<h2>${escapeHtml(line.slice(3))}</h2>`
    if (line.startsWith('# ')) return `<h1>${escapeHtml(line.slice(2))}</h1>`
    if (/^[-*] /.test(line)) return `<ul><li>${escapeHtml(line.slice(2))}</li></ul>`
    if (/^\d+\. /.test(line)) return `<ol><li>${escapeHtml(line.replace(/^\d+\. /, ''))}</li></ol>`
    if (!line) return '<p></p>'
    return `<p>${escapeHtml(line).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/\*(.+?)\*/g, '<em>$1</em>')}</p>`
  }).join('')
}
export function htmlToMarkdown(html: string): string {
  const root = new DOMParser().parseFromString(html, 'text/html').body
  return Array.from(root.children).map((element) => {
    const text = element.textContent ?? ''
    switch (element.tagName.toLowerCase()) {
      case 'h1': return `# ${text}`
      case 'h2': return `## ${text}`
      case 'h3': return `### ${text}`
      case 'ul': return Array.from(element.querySelectorAll(':scope > li')).map((item) => `- ${item.textContent ?? ''}`).join('\n')
      case 'ol': return Array.from(element.querySelectorAll(':scope > li')).map((item, index) => `${index + 1}. ${item.textContent ?? ''}`).join('\n')
      default: return text
    }
  }).join('\n')
}
