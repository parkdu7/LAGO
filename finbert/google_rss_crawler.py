# google_rss_crawler.py
import feedparser
import requests
from datetime import datetime, timedelta
from typing import List, Optional
from dataclasses import dataclass
import logging
import re
from urllib.parse import urlparse, parse_qs, unquote, urlencode
import urllib.parse
import threading

# Selenium 관련 import들 (config에서 RENDER_JS=True일 때만 사용)
try:
    from selenium import webdriver
    from selenium.webdriver.chrome.service import Service as ChromeService
    from selenium.webdriver.chrome.options import Options as ChromeOptions
    from selenium.webdriver.common.by import By
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC
    from selenium.common.exceptions import TimeoutException, WebDriverException
    from webdriver_manager.chrome import ChromeDriverManager
    SELENIUM_AVAILABLE = True
except ImportError:
    SELENIUM_AVAILABLE = False
    logger.warning("Selenium not available. JavaScript rendering disabled.")

logger = logging.getLogger(__name__)


@dataclass
class NewsItem:
    """뉴스 아이템 데이터 클래스"""
    title: str
    url: str
    source: str
    published_at: datetime
    category: str = "경제"
    symbol: Optional[str] = None


class GoogleRSSCrawler:
    """Google RSS 뉴스 크롤러"""

    def __init__(self):
        self.base_url = "https://news.google.com/rss/search"
        self.session = requests.Session()
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        })
        
        # 스레드별 WebDriver 인스턴스 관리
        self._local = threading.local()
        self.webdriver_timeout = 3  # Google News 페이지 로드 타임아웃을 3초로 단축

    def get_webdriver(self):
        """스레드별 WebDriver 인스턴스 반환"""
        from config import RENDER_JS, JS_RENDER_TIMEOUT
        
        if not RENDER_JS or not SELENIUM_AVAILABLE:
            return None
            
        if not hasattr(self._local, 'driver'):
            try:
                chrome_options = ChromeOptions()
                chrome_options.add_argument('--headless')  # 백그라운드 실행
                chrome_options.add_argument('--no-sandbox')
                chrome_options.add_argument('--disable-dev-shm-usage')
                chrome_options.add_argument('--disable-gpu')
                chrome_options.add_argument('--window-size=1920,1080')
                chrome_options.add_argument('--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36')
                
                # Docker 환경 대응
                chrome_options.add_argument('--remote-debugging-port=9222')
                chrome_options.add_argument('--disable-extensions')
                
                service = ChromeService(ChromeDriverManager().install())
                self._local.driver = webdriver.Chrome(service=service, options=chrome_options)
                self._local.driver.set_page_load_timeout(JS_RENDER_TIMEOUT)
                
                logger.debug(f"새 WebDriver 인스턴스 생성 - 스레드: {threading.current_thread().name}")
                
            except Exception as e:
                logger.error(f"WebDriver 초기화 실패: {e}")
                self._local.driver = None
                
        return getattr(self._local, 'driver', None)
    
    def extract_real_url_with_selenium(self, google_url: str) -> Optional[str]:
        """Selenium을 사용한 JavaScript 기반 리디렉션 처리"""
        logger.debug(f"🔍 Selenium 함수 호출됨: {google_url[:80]}...")
        
        driver = self.get_webdriver()
        if not driver:
            logger.warning("⚠️ WebDriver를 사용할 수 없음, 일반 HTTP 방식으로 대체")
            return None
            
        logger.debug("✅ WebDriver 획득 성공")
            
        try:
            logger.debug(f"Selenium으로 JavaScript 리디렉션 처리: {google_url[:80]}...")
            
            # Google News 페이지 로드
            logger.debug(f"페이지 로드 시작: {google_url[:80]}...")
            driver.get(google_url)
            
            # 페이지가 완전히 로드될 때까지 대기
            import time
            time.sleep(3)  # 3초 대기
            
            # 1차: JavaScript 실행 후 현재 URL 확인
            final_url = driver.current_url
            logger.debug(f"현재 URL: {final_url[:80]}...")
            
            # Google 도메인이 아니면 성공
            if not any(domain in final_url for domain in ['google.com', 'googleadservices.com']):
                logger.info(f"✅ Selenium URL 변경 감지: {final_url[:80]}...")
                return final_url
            
            # 2차: 페이지 내 실제 뉴스 링크 적극적으로 찾기
            try:
                # 다양한 선택자로 링크 찾기
                selectors = [
                    'a[href*="://"]',  # 모든 외부 링크
                    'article a',       # 기사 내 링크
                    '.article a',      # 기사 클래스 내 링크
                    '[data-n-tid] a',  # Google News 특정 속성
                    '.VDXfz a',        # Google News CSS 클래스
                    '.WwrzSb a',       # Google News CSS 클래스
                    '.ipQwMb a'        # Google News CSS 클래스
                ]
                
                for selector in selectors:
                    try:
                        links = driver.find_elements(By.CSS_SELECTOR, selector)
                        logger.debug(f"선택자 '{selector}'로 {len(links)}개 링크 발견")
                        
                        for link in links[:5]:  # 상위 5개만 확인
                            try:
                                href = link.get_attribute('href')
                                if href and self._is_news_url(href):
                                    logger.info(f"✅ 실제 뉴스 링크 발견: {href[:80]}...")
                                    return href
                            except:
                                continue
                                
                    except Exception as e:
                        logger.debug(f"선택자 '{selector}' 실패: {e}")
                        continue
                        
            except Exception as e:
                logger.debug(f"링크 검색 전체 실패: {e}")
                
            logger.debug(f"실제 뉴스 링크 찾기 실패: {final_url[:80]}...")
            return None
                
        except TimeoutException:
            logger.warning(f"Selenium 타임아웃: {google_url[:80]}...")
            return None
        except WebDriverException as e:
            logger.warning(f"WebDriver 오류: {e}")
            return None
        except Exception as e:
            logger.error(f"Selenium 리디렉션 오류: {e}")
            return None

    def _is_news_url(self, url: str) -> bool:
        """실제 뉴스 사이트 URL인지 확인"""
        if not url or not isinstance(url, str):
            return False
            
        # Google 도메인 제외
        google_domains = ['google.com', 'googleadservices.com', 'googlesyndication.com', 'gstatic.com']
        if any(domain in url for domain in google_domains):
            return False
            
        # 실제 뉴스 도메인 확인
        news_indicators = ['.co.kr', '.com', '.net', '.org', 'news', 'media', 'press']
        return any(indicator in url for indicator in news_indicators) and url.startswith('http')

    def extract_real_url_from_google(self, google_url: str) -> str:
        """Google News URL에서 실제 뉴스 URL 추출 - Smart 방법"""
        try:
            # Google News URL이 아니면 그대로 반환
            if 'news.google.com' not in google_url:
                return google_url

            # 방법 1: URL 파라미터에서 추출 (빠른 방법)
            parsed = urlparse(google_url)
            params = parse_qs(parsed.query)

            # 'url' 파라미터 확인
            if 'url' in params:
                extracted_url = unquote(params['url'][0])
                if not 'google.com' in extracted_url:
                    logger.debug(f"파라미터에서 URL 추출 성공: {extracted_url[:80]}...")
                    return extracted_url
            
            # 방법 1: googlenewsdecoder (protobuf 디코딩 - 2024년 이후 Google 포맷 대응)
            if '/articles/' in google_url:
                try:
                    from googlenewsdecoder import new_decoderv1
                    decoded = new_decoderv1(google_url, interval=5)
                    if decoded.get("status") and decoded.get("decoded_url"):
                        real_url = decoded["decoded_url"]
                        logger.info(f"✅ googlenewsdecoder 성공: {real_url[:80]}...")
                        return real_url
                except Exception as e:
                    logger.debug(f"googlenewsdecoder 실패: {e}")

            # 방법 2: GET 요청으로 리디렉션 추적 (Fallback)
            try:
                headers = {
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36',
                    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
                    'Accept-Language': 'ko-KR,ko;q=0.8,en-US;q=0.5,en;q=0.3',
                }
                response = requests.get(google_url, allow_redirects=True, timeout=10, headers=headers)
                final_url = response.url
                if not any(domain in final_url for domain in ['google.com', 'googleadservices.com']):
                    logger.info(f"✅ HTTP GET 리디렉션 성공: {final_url[:80]}...")
                    return final_url
            except Exception as e:
                logger.debug(f"GET 리디렉션 실패: {e}")

            # 실패하면 원본 반환
            logger.warning(f"실제 URL 추출 실패, 원본 반환: {google_url[:80]}...")
            return google_url

        except Exception as e:
            logger.error(f"URL 추출 오류: {e}")
            return google_url

    def parse_rss_feed(self, rss_url: str, limit: int = 10) -> List[NewsItem]:
        """RSS 피드 파싱"""
        try:
            logger.info(f"RSS 피드 파싱: {rss_url[:100]}...")
            logger.debug(f"전체 RSS URL: {rss_url}")

            # RSS 피드 가져오기
            feed = feedparser.parse(rss_url)
            
            # 피드 상태 디버깅
            logger.debug(f"피드 상태 - status: {getattr(feed, 'status', 'N/A')}")
            logger.debug(f"피드 버전: {getattr(feed, 'version', 'N/A')}")
            logger.debug(f"피드 에러: {getattr(feed, 'bozo', False)}")
            if hasattr(feed, 'bozo_exception'):
                logger.error(f"피드 파싱 에러: {feed.bozo_exception}")

            if not feed.entries:
                logger.warning(f"RSS 피드에 항목이 없습니다 - entries 수: {len(feed.entries)}")
                # 피드 내용 일부 출력
                if hasattr(feed, 'feed'):
                    logger.debug(f"피드 제목: {getattr(feed.feed, 'title', 'N/A')}")
                    logger.debug(f"피드 링크: {getattr(feed.feed, 'link', 'N/A')}")
                return []

            news_items = []
            processed_urls = set()  # 중복 URL 체크

            for entry in feed.entries[:limit * 2]:  # 여유있게 가져오기
                try:
                    # Google News URL
                    google_url = entry.get('link', '')

                    if not google_url:
                        continue

                    # 실제 URL 추출
                    real_url = self.extract_real_url_from_google(google_url)

                    # Google News 페이지 처리 완화 - 실제 뉴스 사이트 URL만 우선 처리
                    if 'news.google.com' in real_url:
                        # 실제 뉴스 사이트 URL을 못 찾은 경우에만 Google News URL도 허용
                        logger.debug(f"Google News URL 발견, 계속 처리: {real_url[:80]}...")
                        # continue 제거 - Google News URL도 처리하도록 변경

                    # 중복 체크
                    if real_url in processed_urls:
                        continue
                    processed_urls.add(real_url)

                    # 제목 추출
                    title = entry.get('title', '').strip()
                    if not title or len(title) < 10:
                        continue

                    # 소스 추출 (Google RSS는 제목에 소스 포함)
                    source = "Unknown"
                    if ' - ' in title:
                        parts = title.rsplit(' - ', 1)
                        if len(parts) == 2:
                            title = parts[0]
                            source = parts[1]

                    # 발행 시간
                    published = entry.get('published_parsed', None)
                    if published:
                        published_at = datetime.fromtimestamp(
                            datetime(*published[:6]).timestamp()
                        )
                    else:
                        published_at = datetime.now()

                    news_item = NewsItem(
                        title=title,
                        url=real_url,
                        source=source,
                        published_at=published_at,
                        category="경제"
                    )

                    news_items.append(news_item)
                    logger.debug(f"뉴스 추가: {title[:50]}... ({source})")

                    if len(news_items) >= limit:
                        break

                except Exception as e:
                    logger.error(f"항목 파싱 오류: {e}")
                    continue

            logger.info(f"RSS 피드에서 {len(news_items)}개 뉴스 수집")
            return news_items

        except Exception as e:
            logger.error(f"RSS 피드 파싱 실패: {e}")
            return []

    def get_realtime_news(self, limit: int = 20) -> List[NewsItem]:
        """실시간 경제 뉴스 수집"""
        try:
            # 한국 경제 뉴스 검색어들
            queries = [
                "경제 OR 증시 OR 주식",
                "코스피 OR 코스닥",
                "금융 OR 투자"
            ]

            all_news = []
            items_per_query = max(limit // len(queries), 5)

            for query in queries:
                params = {
                    'q': query,
                    'hl': 'ko',
                    'gl': 'KR',
                    'ceid': 'KR:ko'
                }

                # 쿼리 파라미터를 안전하게 URL로 변환 (URL 인코딩 포함)
                query_string = urllib.parse.urlencode(params)
                rss_url = f"{self.base_url}?{query_string}"

                news_items = self.parse_rss_feed(rss_url, items_per_query)
                all_news.extend(news_items)

            # 중복 제거 및 시간순 정렬
            seen_urls = set()
            unique_news = []

            for item in sorted(all_news, key=lambda x: x.published_at, reverse=True):
                if item.url not in seen_urls:
                    seen_urls.add(item.url)
                    unique_news.append(item)

            return unique_news[:limit]

        except Exception as e:
            logger.error(f"실시간 뉴스 수집 실패: {e}")
            return []

    def get_watchlist_news(self, symbol: str, company_name: str,
                           aliases: List[str], limit: int = 5) -> List[NewsItem]:
        """관심종목 뉴스 수집"""
        try:
            # 검색어 구성
            search_terms = [company_name] + (aliases if aliases else [])
            query = ' OR '.join(f'"{term}"' for term in search_terms)

            params = {
                'q': query,
                'hl': 'ko',
                'gl': 'KR',
                'ceid': 'KR:ko'
            }

            # 쿼리 파라미터를 안전하게 URL로 변환 (URL 인코딩 포함)
            query_string = urllib.parse.urlencode(params)
            rss_url = f"{self.base_url}?{query_string}"

            news_items = self.parse_rss_feed(rss_url, limit)

            # 종목 정보 설정
            for item in news_items:
                item.symbol = symbol
                item.category = "관심종목"

            return news_items

        except Exception as e:
            logger.error(f"관심종목 뉴스 수집 실패 {company_name}: {e}")
            return []

    def get_historical_news(self, symbol: str, company_name: str,
                            date: str, aliases: List[str], limit: int = 10) -> List[NewsItem]:
        """특정 날짜 뉴스 수집"""
        try:
            # 날짜 범위 설정 (해당일 ± 1일)
            target_date = datetime.strptime(date, "%Y-%m-%d")
            date_before = (target_date - timedelta(days=1)).strftime("%Y-%m-%d")
            date_after = (target_date + timedelta(days=1)).strftime("%Y-%m-%d")

            # 검색어 구성
            search_terms = [company_name] + (aliases if aliases else [])
            query = ' OR '.join(f'"{term}"' for term in search_terms)
            query += f' after:{date_before} before:{date_after}'

            params = {
                'q': query,
                'hl': 'ko',
                'gl': 'KR',
                'ceid': 'KR:ko'
            }

            # 쿼리 파라미터를 안전하게 URL로 변환 (URL 인코딩 포함)
            query_string = urllib.parse.urlencode(params)
            rss_url = f"{self.base_url}?{query_string}"

            news_items = self.parse_rss_feed(rss_url, limit)

            # 종목 정보 설정
            for item in news_items:
                item.symbol = symbol
                item.category = "역사챌린지"

            return news_items

        except Exception as e:
            logger.error(f"역사적 뉴스 수집 실패 {company_name} ({date}): {e}")
            return []

    def cleanup(self):
        """리소스 정리"""
        try:
            self.session.close()
        except:
            pass
            
        # WebDriver 정리
        if hasattr(self._local, 'driver') and self._local.driver:
            try:
                self._local.driver.quit()
                logger.debug("WebDriver 정리 완료")
            except Exception as e:
                logger.error(f"WebDriver 정리 오류: {e}")
            finally:
                del self._local.driver