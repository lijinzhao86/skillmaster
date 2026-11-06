/**
 * A skill's 头像：列表行与详情页共用的一份。
 *
 * 默认是名字首字母大写 + 由整条名字决定的底色（同一条每次渲染都一样，不是真随机）。
 * 两处各写一份的代价是颜色会先漂一处：同一条 skill 在列表里是琥珀、进详情页变玫红，
 * 读的人会以为那是两条不同的东西。
 *
 * 本版没有官方图标（prd 不做 #12），所以这里只有默认值。
 */

const TONES = 6

export default function SkillAvatar({ name }: { name: string }) {
  const initial = name.trim().charAt(0).toUpperCase() || '?'
  const tone = [...name].reduce((sum, ch) => (sum + ch.codePointAt(0)!) % TONES, 0)
  return (
    <span className="result-avatar" data-tone={tone} aria-hidden="true">
      {initial}
    </span>
  )
}
