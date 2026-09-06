"""Report headings and safe Markdown links, without services or browser dependencies."""
from pathlib import Path
import tempfile
import unittest

from study_html import inline, write_html


class StudyHtmlTest(unittest.TestCase):
    def test_links_preserve_code_emphasis_and_escape_html(self):
        value = inline('**[NIST `sign` & test](https://example.org/test?a=1&b=2)** '
                       '`[literal](https://example.org)` <script>bad()</script>')
        self.assertIn('<strong><a href="https://example.org/test?a=1&amp;b=2">NIST <code>sign</code> &amp; test</a></strong>', value)
        self.assertIn('<code>[literal](https://example.org)</code>', value)
        self.assertIn('&lt;script&gt;bad()&lt;/script&gt;', value)
        self.assertNotIn('<script>', value)

    def test_only_http_https_and_fragments_become_links(self):
        for target in ('http://example.org', 'https://example.org', '#wyniki'):
            with self.subTest(target=target):
                self.assertEqual(f'<a href="{target}">Opis</a>', inline(f'[Opis]({target})'))
        for target in ('javascript:alert(1)', 'data:text/html,content', 'file:///tmp/report.html',
                       '//example.org', '/report.html', 'mailto:a@example.org',
                       'https://[bad', '#`unsafe`'):
            with self.subTest(target=target):
                self.assertNotIn('<a ', inline(f'[Opis]({target})'))

    def test_link_url_punctuation_is_not_emphasis_or_html(self):
        value = inline('[Źródło](https://example.org/*part*?a="x"&b=2)')
        self.assertIn('href="https://example.org/*part*?a=&quot;x&quot;&amp;b=2"', value)
        self.assertNotIn('<em>', value)

    def test_document_title_uses_first_main_heading_and_escapes_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)/'20260906-123456'
            root.mkdir()
            output = root/'report.html'
            write_html(root, '# **Raport** `PQL` & <wyniki>\n\n# Drugi tytuł', output)
            html = output.read_text()
            self.assertIn('<title>Raport PQL &amp; &lt;wyniki&gt;</title>', html)
            self.assertNotIn('<title>Raport benchmarku — 20260906-123456</title>', html)
            write_html(root, 'Bez nagłówka.', output)
            self.assertIn('<title>Raport benchmarku</title>', output.read_text())


if __name__ == '__main__':
    unittest.main()
